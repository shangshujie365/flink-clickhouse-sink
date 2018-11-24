package ru.ivi.opensource.flinkclickhousesink.applied;

import com.google.common.collect.Lists;
import io.netty.handler.codec.http.HttpHeaders;
import org.asynchttpclient.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ivi.opensource.flinkclickhousesink.model.ClickhouseRequestBlank;
import ru.ivi.opensource.flinkclickhousesink.model.ClickhouseSinkCommonParams;
import ru.ivi.opensource.flinkclickhousesink.util.ThreadUtil;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.*;


public class ClickhouseWriter implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ClickhouseWriter.class);

    private ExecutorService service;
    private ExecutorService callbackService;
    private List<WriterTask> tasks;
    private BlockingQueue<ClickhouseRequestBlank> commonQueue;

    private ClickhouseSinkCommonParams sinkParams;

    public ClickhouseWriter(ClickhouseSinkCommonParams sinkParams) {
        this.sinkParams = sinkParams;
        initDirAndExecutors();
    }

    private void initDirAndExecutors() {
        try {
            initDir(sinkParams.getFailedRecordsPath());
            buildAndRunExecs();
        } catch (Exception e) {
            logger.error("Error during start CH writers", e);
            throw new RuntimeException(e);
        }
    }

    private static void initDir(String pathName) throws IOException {
        Path path = Paths.get(pathName);
        Files.createDirectories(path);
    }

    private void buildAndRunExecs() {
        int numWriters = sinkParams.getNumWriters();

        commonQueue = new LinkedBlockingQueue<>(sinkParams.getQueueMaxCapacity());

        ThreadFactory threadFactory = ThreadUtil.threadFactory("clickhouse-writer");
        service = Executors.newFixedThreadPool(sinkParams.getNumWriters(), threadFactory);

        ThreadFactory callbackServiceFactory = ThreadUtil.threadFactory("clickhouse-writer-callback-executor");
        callbackService = Executors.newCachedThreadPool(callbackServiceFactory);

        tasks = Lists.newArrayList();
        for (int i = 0; i < numWriters; i++) {
            WriterTask task = new WriterTask(i, commonQueue, sinkParams, callbackService);
            tasks.add(task);
            service.submit(task);
        }
    }

    public void put(ClickhouseRequestBlank params) {
        try {
            commonQueue.put(params);
        } catch (InterruptedException e) {
            logger.error("Interrupted error while putting data to queue", e);
            throw new RuntimeException(e);
        }
    }

    private void stopWriters() {
        if (tasks != null && tasks.size() > 0) {
            tasks.forEach(WriterTask::setStopWorking);
        }
    }

    @Override
    public void close() throws Exception {
        logger.info("Closing clickhouse-writer...");
        stopWriters();
        ThreadUtil.shutdownExecutorService(service);
        ThreadUtil.shutdownExecutorService(callbackService);
        logger.info("{} is closed", ClickhouseWriter.class.getSimpleName());
    }

    static class WriterTask implements Runnable {
        private static final Logger logger = LoggerFactory.getLogger(WriterTask.class);

        private static final int HTTP_OK = 200;

        private final BlockingQueue<ClickhouseRequestBlank> queue;
        private final ClickhouseSinkCommonParams sinkSettings;
        private final AsyncHttpClient asyncHttpClient;
        private final ExecutorService callbackService;

        private final int id;

        private volatile boolean isWorking;

        WriterTask(int id,
                   BlockingQueue<ClickhouseRequestBlank> queue,
                   ClickhouseSinkCommonParams settings,
                   ExecutorService callbackService
        ) {
            this.id = id;
            this.sinkSettings = settings;
            this.queue = queue;
            this.callbackService = callbackService;

            this.asyncHttpClient = Dsl.asyncHttpClient();
        }

        @Override
        public void run() {
            try {
                isWorking = true;

                logger.info("Start writer task, id = {}", id);
                while (isWorking || queue.size() > 0) {
                    ClickhouseRequestBlank blank = queue.poll(300, TimeUnit.MILLISECONDS);
                    if (blank != null) {
                        send(blank);
                    }
                }
            } catch (Exception e) {
                logger.error("Error while inserting data", e);
                throw new RuntimeException(e);
            } finally {
                logger.info("Task id = {} is finished", id);
            }
        }

        private void send(ClickhouseRequestBlank requestBlank) {
            Request request = buildRequest(requestBlank);

            logger.debug("Ready to load data to {}, size = {}", requestBlank.getTargetTable(), requestBlank.getValues().size());
            ListenableFuture<Response> whenResponse = asyncHttpClient.executeRequest(request);

            Runnable callback = callbackLambda(whenResponse, requestBlank);
            whenResponse.addListener(callback, callbackService);
        }

        private Request buildRequest(ClickhouseRequestBlank requestBlank) {
            String resultCSV = String.join(" , ", requestBlank.getValues());
            String query = String.format("INSERT INTO %s VALUES %s", requestBlank.getTargetTable(), resultCSV);
            String host = sinkSettings.getClickhouseClusterSettings().getRandomHostUrl();

            BoundRequestBuilder builder = asyncHttpClient
                    .preparePost(host)
                    .setBody(query);

            if (sinkSettings.getClickhouseClusterSettings().isAuthorizationRequired()) {
                builder.setHeader(HttpHeaders.Names.AUTHORIZATION, "Basic " + sinkSettings.getClickhouseClusterSettings().getCredentials());
            }

            return builder.build();
        }

        private Runnable callbackLambda(ListenableFuture<Response> whenResponse, ClickhouseRequestBlank requestBlank) {
            return () -> {
                try {
                    Response response = whenResponse.get();

                    if (response.getStatusCode() != HTTP_OK) {
                        handleUnsuccessfulResponse(response, requestBlank);
                    } else {
                        logger.info("Successful send data to Clickhouse, batch size = {}, target table = {}, current attempt = {}",
                                requestBlank.getValues().size(),
                                requestBlank.getTargetTable(),
                                requestBlank.getAttemptCounter().get());
                    }
                } catch (Exception e) {
                    logger.error("Error while executing callback, params = {}", sinkSettings, e);
                    try {
                        logFailedRecords(requestBlank);
                    } catch (Exception error) {
                        logger.error("Error while sending data on disk", error);
                    }
                }
            };
        }

        private void handleUnsuccessfulResponse(Response response, ClickhouseRequestBlank requestBlank) throws Exception {
            int currentCounter = requestBlank.getAttemptCounter().get();
            if (currentCounter > sinkSettings.getMaxRetries()) {
                logger.warn("Failed to send data to Clickhouse, cause: limit of attempts is exceeded. Clickhouse response = {}. Ready to flush data on disk", response);
                logFailedRecords(requestBlank);
            } else {
                requestBlank.getAttemptCounter().incrementAndGet();
                logger.warn("Next attempt to send data to Clickhouse, table = {}, buffer size = {}, current attempt num = {}, max attempt num = {}, response = {}",
                        requestBlank.getTargetTable(),
                        requestBlank.getValues().size(),
                        requestBlank.getAttemptCounter().get(),
                        sinkSettings.getMaxRetries(),
                        response);
                queue.put(requestBlank);
            }
        }

        private void logFailedRecords(ClickhouseRequestBlank requestBlank) throws Exception {
            String filePath = String.format("%s/%s_%s",
                    sinkSettings.getFailedRecordsPath(),
                    requestBlank.getTargetTable(),
                    System.currentTimeMillis());

            try (PrintWriter writer = new PrintWriter(filePath)) {
                writer.println(requestBlank.getValues());
                writer.flush();
            }
            logger.info("Successful send data on disk, path = {}, size = {} ", filePath, requestBlank.getValues().size());
        }

        void setStopWorking() {
            isWorking = false;
        }
    }
}