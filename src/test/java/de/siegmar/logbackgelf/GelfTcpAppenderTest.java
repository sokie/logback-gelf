/*
 * Logback GELF - zero dependencies Logback GELF appender library.
 * Copyright (C) 2016 Oliver Siegmar
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package de.siegmar.logbackgelf;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.ByteStreams;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GelfTcpAppenderTest {

    private static final String LOGGER_NAME = GelfTcpAppenderTest.class.getCanonicalName();

    private TcpServerRunnable server;

    @Before
    public void before() throws IOException, InterruptedException {
        server = new TcpServerRunnable();
        final Thread serverThread = new Thread(server);
        serverThread.start();
    }

    @Test
    public void simple() throws IOException, InterruptedException {
        final Logger logger = setupLogger();

        logger.error("Test message");

        stopLogger(logger);

        final JsonNode jsonNode = receiveMessage();
        assertEquals("1.1", jsonNode.get("version").textValue());
        assertEquals("localhost", jsonNode.get("host").textValue());
        assertEquals("Test message", jsonNode.get("short_message").textValue());
        assertTrue(jsonNode.get("timestamp").isNumber());
        assertEquals(3, jsonNode.get("level").intValue());
        assertEquals("main", jsonNode.get("_thread_name").textValue());
        assertEquals(LOGGER_NAME, jsonNode.get("_logger_name").textValue());
    }

    private Logger setupLogger() {
        final LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();

        final GelfLayout gelfLayout = new GelfLayout();
        gelfLayout.setContext(lc);
        gelfLayout.setOriginHost("localhost");
        gelfLayout.start();

        final Logger logger = (Logger) LoggerFactory.getLogger(LOGGER_NAME);
        logger.addAppender(buildAppender(lc, gelfLayout));
        logger.setAdditive(false);

        return logger;
    }

    private GelfTcpAppender buildAppender(final LoggerContext lc, final GelfLayout gelfLayout) {
        final GelfTcpAppender gelfAppender = new GelfTcpAppender();
        gelfAppender.setContext(lc);
        gelfAppender.setName("GELF");
        gelfAppender.setLayout(gelfLayout);
        gelfAppender.setGraylogHost("localhost");
        gelfAppender.setGraylogPort(server.getPort());
        gelfAppender.start();
        return gelfAppender;
    }

    private JsonNode receiveMessage() throws IOException, InterruptedException {
        return new ObjectMapper().readTree(server.getReceivedData());
    }

    private void stopLogger(final Logger logger) throws IOException {
        GelfTcpAppender gelfAppender = (GelfTcpAppender) logger.getAppender("GELF");
        gelfAppender.stop();
    }

}

final class TcpServerRunnable implements Runnable {

    private final ServerSocket server;
    private byte[] receivedData;
    private Semaphore semaphore = new Semaphore(1);

    TcpServerRunnable() throws IOException, InterruptedException {
        server = new ServerSocket(0);
        semaphore.acquire();
    }

    int getPort() {
        return server.getLocalPort();
    }

    byte[] getReceivedData() {
        try {
            if (!semaphore.tryAcquire(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Couldn't acquire semaphore!");
            }
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        return receivedData;
    }

    @Override
    public void run() {
        try (final Socket socket = server.accept()) {
            try (final DataInputStream in = new DataInputStream(socket.getInputStream())) {
                receivedData = ByteStreams.toByteArray(in);
            }
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }

        try {
            server.close();
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }

        semaphore.release();
    }

}