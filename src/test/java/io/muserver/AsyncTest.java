package io.muserver;

import io.netty.channel.Channel;
import okhttp3.*;
import okio.BufferedSink;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scaffolding.MuAssert;
import scaffolding.ServerUtils;
import scaffolding.StringUtils;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static io.muserver.MuServerBuilder.httpsServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertTrue;
import static scaffolding.ClientUtils.*;

public class AsyncTest {

    private static final Logger log = LoggerFactory.getLogger(AsyncTest.class);

    private MuServer server;

    @Test
    public void canWriteAsync() throws Exception {
        byte[] bytes = StringUtils.randomBytes(120000);
        StringBuffer result = new StringBuffer();
        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.contentType(ContentTypes.APPLICATION_OCTET_STREAM);
                AsyncHandle asyncHandle = request.handleAsync();
                asyncHandle.write(ByteBuffer.wrap(bytes), error -> {
                    if (error == null) {
                        result.append("success");
                        asyncHandle.complete();
                    } else {
                        result.append("fail ").append(error);
                        asyncHandle.complete();
                    }
                });
            })
            .start();
        try (Response resp = call(request().url(server.uri().toString()))) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().bytes(), equalTo(bytes));
            assertThat(result.toString(), equalTo("success"));
        }
    }

    @Test
    public void canWriteAsyncAndDoneCallbackWillDelayWhenNotWritable() throws Exception {

        AtomicInteger sendDoneCallbackCount = new AtomicInteger(0);
        AtomicInteger receivedCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        CountDownLatch requestUnWrtiable = new CountDownLatch(1);
        CountDownLatch testDone = new CountDownLatch(1);
        AtomicBoolean isDoneCallBackCountLessThan64 = new AtomicBoolean(false);

        int totalCount = 1000;
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Config()) // test http 1 only
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.contentType(ContentTypes.APPLICATION_OCTET_STREAM);
                byte[] sendByte = StringUtils.randomBytes(1024);
                NettyRequestAdapter.AsyncHandleImpl asyncHandle = (NettyRequestAdapter.AsyncHandleImpl)request.handleAsync();
                ExecutorService executorService = Executors.newFixedThreadPool(1);

                for (int i = 0; i < totalCount; i++) {
                    asyncHandle.write(ByteBuffer.wrap(sendByte), error -> {
                        sendDoneCallbackCount.incrementAndGet();
                    });
                }
                
                Field requestField = asyncHandle.getClass().getDeclaredField("request");
                requestField.setAccessible(true);
                NettyRequestAdapter requestAdapter = (NettyRequestAdapter) requestField.get(asyncHandle);
                Field channelField = requestAdapter.getClass().getDeclaredField("channel");
                channelField.setAccessible(true);
                Channel channel = (Channel) channelField.get(requestAdapter);
                
                executorService.submit(()->{
                    while (true){
                        if (!channel.isWritable()){
                            requestUnWrtiable.countDown();
                            break;
                        }
                        try {
                            Thread.sleep(1);
                        } catch (InterruptedException e){
                            //fail to sleep do nothing.
                        }
                    }});

                MuAssert.assertEventually(sendDoneCallbackCount::get, is(totalCount));
                executorService.shutdown();
                asyncHandle.complete();
            })
            .start();

        try (Response resp = call(request().url(server.uri().toString()))) {
            assertThat(resp.code(), equalTo(200));

            byte[] readBytes = new byte[1024];

            // http client read the first 1024 byte and then sleep,
            // verify server done callback not exceeding 64 time, as
            // it can't write out given the netty highWaterMark set to 64k
            resp.body().byteStream().read(readBytes);
            receivedCount.incrementAndGet();
            
            ExecutorService executorService = Executors.newFixedThreadPool(1);
            executorService.submit(()->{
                try {
                    requestUnWrtiable.await();
                    if (sendDoneCallbackCount.get() < 64) {
                        isDoneCallBackCountLessThan64.getAndSet(true);
                        testDone.countDown();
                    }
                } catch (InterruptedException e) {
                    failureCount.getAndIncrement();
                }
            });

            testDone.await();
            assertTrue(isDoneCallBackCountLessThan64.get());
            assertThat(failureCount.get(), is(0));
            executorService.shutdown();

            // http client read the rest bytes, verify all data received
            while (resp.body().byteStream().read(readBytes) != -1) {
                receivedCount.incrementAndGet();
            }
            
            assertThat(sendDoneCallbackCount.get(), is(totalCount));
            assertThat(receivedCount.get(), is(totalCount));
        }
    }

    @Test
    public void responsesCanBeAsync() throws IOException {

        DatabaseListenerSimulator changeListener = new DatabaseListenerSimulator(10);

        server = ServerUtils.httpsServerForTest()
            .addHandler((request, response) -> {
                response.headers().add("X-Pre-Header", "Hello");
                return false;
            })
            .addHandler((request, response) -> {
                AsyncHandle ctx = request.handleAsync();

                changeListener.addListener(new ChangeListener() {
                    @Override
                    public void onData(String data) {
                        ctx.write(Mutils.toByteBuffer(data + "\n"));
                    }

                    @Override
                    public void onClose() {
                        ctx.complete();
                    }
                });

                changeListener.start();

                return true;
            })
            .addHandler((request, response) -> {
                response.headers().add("X-Post-Header", "Goodbye");
                return false;
            })
            .start();

        try (Response resp = call(request().url(server.uri().toString()))) {
            assertThat(changeListener.errors, is(empty()));
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.header("X-Pre-Header"), equalTo("Hello"));
            assertThat(resp.header("X-Post-Header"), is(nullValue()));
            assertThat(resp.body().string(), equalTo("Loop 0\nLoop 1\nLoop 2\nLoop 3\nLoop 4\nLoop 5\nLoop 6\nLoop 7\nLoop 8\nLoop 9\n"));
        }
    }

    @Test
    public void errorCallbacksHappenIfTheClientDisconnects() throws IOException, InterruptedException {

        DatabaseListenerSimulator changeListener = new DatabaseListenerSimulator(Integer.MAX_VALUE);
        CountDownLatch timedOutLatch = new CountDownLatch(1);
        CountDownLatch ctxClosedLatch = new CountDownLatch(1);
        List<Throwable> writeErrors = new ArrayList<>();

        AtomicLong connectionsDuringListening = new AtomicLong();

        server = ServerUtils.httpsServerForTest()
            .addHandler((request, response) -> {
                AsyncHandle ctx = request.handleAsync();
                connectionsDuringListening.set(request.server().stats().activeConnections());
                changeListener.addListener(new ChangeListener() {
                    public void onData(String data) {
                        try {
                            ByteBuffer text = Mutils.toByteBuffer(data + "\n");
                            ctx.write(text, error -> {
                                if (error != null) {
                                    changeListener.stop();
                                    ctx.complete();
                                    ctxClosedLatch.countDown();
                                }
                            });
                        } catch (Throwable e) {
                            writeErrors.add(e);
                        }
                    }

                    public void onClose() {
                    }
                });

                timedOutLatch.await(1, TimeUnit.MINUTES);
                changeListener.start();

                return true;
            })
            .start();

        OkHttpClient impatientClient = client.newBuilder()
            .readTimeout(100, TimeUnit.MILLISECONDS)
            .build();
        try (Response resp = impatientClient.newCall(request().url(server.uri().toString()).build()).execute()) {
            assertThat(changeListener.errors, is(empty()));
            assertThat(resp.code(), equalTo(200));
            resp.body().string();
            Assert.fail("Should have timeout out");
        } catch (SocketTimeoutException to) {
            timedOutLatch.countDown();
            assertThat("Timed out waiting for failure callback to happen",
                ctxClosedLatch.await(30, TimeUnit.SECONDS), is(true));
            assertThat(writeErrors, is(empty()));
        }

        assertThat(connectionsDuringListening.get(), is(1L));
        assertThat(server.stats().activeRequests().size(), is(0));
        assertThat(server.stats().completedRequests(), is(1L));
    }

    @Test
    public void blockingWritesCanStillBeUsed() throws IOException {

        DatabaseListenerSimulator changeListener = new DatabaseListenerSimulator(10);

        server = ServerUtils.httpsServerForTest()
            .addHandler((request, response) -> {
                AsyncHandle ctx = request.handleAsync();

                changeListener.addListener(new ChangeListener() {
                    public void onData(String data) {
                        response.writer().write(data + "\n");
                    }

                    public void onClose() {
                        ctx.complete();
                    }
                });

                changeListener.start();

                return true;
            })
            .start();

        try (Response resp = call(request().url(server.uri().toString()))) {
            assertThat(changeListener.errors, is(empty()));
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().string(), equalTo("Loop 0\nLoop 1\nLoop 2\nLoop 3\nLoop 4\nLoop 5\nLoop 6\nLoop 7\nLoop 8\nLoop 9\n"));
        }
    }

    @Test
    public void requestBodiesCanBeReadAsynchronously() throws IOException {
        List<Throwable> errors = new ArrayList<>();
        server = ServerUtils.httpsServerForTest()
            .addHandler((request, response) -> {
                AsyncHandle handle = request.handleAsync();
                handle.setReadListener(new RequestBodyListener() {
                    @Override
                    public void onDataReceived(ByteBuffer bb, DoneCallback doneCallback) throws Exception {
                        handle.write(bb, error -> {
                            if (error != null) {
                                errors.add(error);
                            }
                            doneCallback.onComplete(error);
                        });
                    }

                    @Override
                    public void onComplete() {
                        handle.complete();
                    }

                    @Override
                    public void onError(Throwable t) {
                        errors.add(t);
                    }
                });

                return true;
            })
            .start();

        Request.Builder request = request()
            .url(server.uri().toString())
            .post(new RequestBody() {
                @Override
                public MediaType contentType() {
                    return MediaType.parse("text/plain");
                }

                @Override
                public void writeTo(BufferedSink sink) throws IOException {
                    for (int i = 0; i < 10; i++) {
                        sink.writeUtf8("Loop " + i + "\n");
                        try {
                            Thread.sleep(70);
                        } catch (InterruptedException e) {
                            errors.add(e);
                        }
                    }
                }
            });

        try (Response resp = call(request)) {
            assertThat(errors, is(empty()));
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().string(), equalTo("Loop 0\nLoop 1\nLoop 2\nLoop 3\nLoop 4\nLoop 5\nLoop 6\nLoop 7\nLoop 8\nLoop 9\n"));
        }

    }


    interface ChangeListener {
        void onData(String data);
        void onClose();
    }

    static class DatabaseListenerSimulator {
        private final int eventsToFire;
        private List<ChangeListener> listeners = new ArrayList<>();

        private final Random rng = new Random();
        public final List<Throwable> errors = new ArrayList<>();
        private AtomicBoolean stopped = new AtomicBoolean(false);
        private Thread thread;

        DatabaseListenerSimulator(int eventsToFire) {
            this.eventsToFire = eventsToFire;
        }

        public void start() {
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    for (int i = 0; i < eventsToFire; i++) {
                        if (stopped.get()) {
                            break;
                        }
                        try {
                            Thread.sleep(rng.nextInt(100));
                        } catch (InterruptedException e) {
                            break;
                        }
                        for (ChangeListener listener : listeners) {
                            try {
                                listener.onData("Loop " + i);
                            } catch (Throwable e) {
                                errors.add(e);
                            }
                        }
                    }
                    for (ChangeListener listener : listeners) {
                        try {
                            listener.onClose();
                        } catch (Throwable e) {
                            errors.add(e);
                        }
                    }

                }
            });
            thread.start();
        }

        public void addListener(ChangeListener listener) {
            this.listeners.add(listener);
        }

        public void stop() throws InterruptedException {
            stopped.set(true);
            thread.join();
        }
    }


    @After
    public void destroy() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

}
