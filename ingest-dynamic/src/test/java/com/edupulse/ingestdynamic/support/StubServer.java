package com.edupulse.ingestdynamic.support;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpServer;

/** A tiny local HTTP server that serves canned responses, for tests that need real HTTP. */
public final class StubServer implements AutoCloseable {

	private final HttpServer server;

	private StubServer(HttpServer server) {
		this.server = server;
	}

	public static StubServer start() throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.start();
		return new StubServer(server);
	}

	/** A port nothing is listening on, for connection-refused tests. */
	public static int unusedPort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
			return socket.getLocalPort();
		}
	}

	public void respond(String path, int status, String contentType, String body) {
		respond(path, status, contentType, body.getBytes(StandardCharsets.UTF_8));
	}

	public void respond(String path, int status, String contentType, byte[] body) {
		server.createContext(path, exchange -> {
			exchange.getResponseHeaders().add("Content-Type", contentType);
			exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
			if (body.length > 0) {
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(body);
				}
			}
			exchange.close();
		});
	}

	public void redirect(String path, String location) {
		server.createContext(path, exchange -> {
			exchange.getResponseHeaders().add("Location", location);
			exchange.sendResponseHeaders(302, -1);
			exchange.close();
		});
	}

	public String url(String path) {
		return "http://127.0.0.1:" + server.getAddress().getPort() + path;
	}

	@Override
	public void close() {
		server.stop(0);
	}

}
