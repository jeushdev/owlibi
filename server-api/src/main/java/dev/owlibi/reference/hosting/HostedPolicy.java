package dev.owlibi.reference.hosting;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Set;

/** Target configuration is server-only. No permissive TLS or wildcard Origin fallback. */
public final class HostedPolicy {
    private HostedPolicy() {}
    public static String origin(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null || !uri.getRawPath().isEmpty()
                || (uri.getPort() != -1 && uri.getPort() != 443) || value.contains("*")
                || !value.equals(uri.toASCIIString())) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("An exact HTTPS frontend origin is required"); }
    }
    public static String jdbc(String value) {
        try {
            if (!value.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException();
            URI uri = URI.create(value.substring(5));
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                || uri.getPath() == null || uri.getPath().length() < 2 || uri.getRawQuery() == null) throw new IllegalArgumentException();
            var parameters = new HashMap<String,String>();
            for (String part : uri.getRawQuery().split("&", -1)) {
                String[] pair = part.split("=", 2);
                if (pair.length != 2) throw new IllegalArgumentException();
                String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                String val = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                if (!Set.of("currentSchema", "sslmode", "sslrootcert", "connectTimeout", "socketTimeout").contains(key)
                    || parameters.putIfAbsent(key, val) != null) throw new IllegalArgumentException();
            }
            if (!"owlibi".equals(parameters.get("currentSchema")) || !"verify-full".equals(parameters.get("sslmode"))
                || !"3".equals(parameters.get("connectTimeout")) || !"5".equals(parameters.get("socketTimeout"))) throw new IllegalArgumentException();
            Path certificate = Path.of(parameters.get("sslrootcert"));
            if (!certificate.isAbsolute() || !Files.isRegularFile(certificate) || !Files.isReadable(certificate)) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("Strict database TLS configuration is required"); }
    }
}
