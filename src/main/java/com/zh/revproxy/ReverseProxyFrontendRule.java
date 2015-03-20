package com.zh.revproxy;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.util.List;
import java.util.Map;

public class ReverseProxyFrontendRule {
	private final ReverseProxyBackend backend;
	private String pathPrefix;
	private String authorizationHeaderSuffix;
	private String sessionKeySuffix;
	private boolean removePrefix;
	
	public ReverseProxyFrontendRule(ReverseProxyBackend backend, String pathPrefix, boolean removePrefix, String authorizationHeaderSuffix,
		String sessionKeySuffix) {
		this.backend = backend;
		this.pathPrefix = pathPrefix;
		this.removePrefix = removePrefix;
		this.authorizationHeaderSuffix = authorizationHeaderSuffix;
		this.sessionKeySuffix = sessionKeySuffix;
	}
	
	public boolean matches(HttpRequest request) {
		if (this.pathPrefix != null && request.getUri().startsWith(this.pathPrefix)) {
			if (removePrefix) {
				// if the request URI is "http://google.com/dev/" cut only "/dev", because the prefix is "/dev/" (with a slash in the end)
				String removedPrefix = request.getUri().substring(this.pathPrefix.length() - 1);
				request.setUri(removedPrefix);
			}
			return true;
		}
		
		if (this.authorizationHeaderSuffix != null && request.headers() != null) {
			if (request.headers().get("Authorization") != null && request.headers().get("Authorization").endsWith(authorizationHeaderSuffix)) {
				return true;
			}
		}
		
		if (this.sessionKeySuffix != null && request.getUri() != null) {
			String uriString = request.getUri();
			int queryStart = uriString.indexOf("?");
			if (queryStart != -1) {
				Map<String, List<String>> query = splitQuery(uriString.substring(queryStart));
				if (query != null) {
					List<String> sessionKey = query.get("sessionKey");
					if (sessionKey != null && !sessionKey.isEmpty()) {
						if (sessionKey.get(0).endsWith(this.sessionKeySuffix)) {
							return true;
						}
					}
				}
			}
		}
		
		return false;
	}
	
	public static Map<String, List<String>> splitQuery(String query) {
		QueryStringDecoder queryStringDecoder = new QueryStringDecoder(query);
		Map<String, List<String>> params = queryStringDecoder.parameters();
		return params;
	}
	
	public ReverseProxyBackend getBackend() {
		return backend;
	}
	
}
