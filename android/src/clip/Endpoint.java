package clip;

import java.net.URI;

final class Endpoint {
    final String host;
    final int port;

    private Endpoint(String host, int port) {
        this.host = host;
        this.port = port;
    }

    static Endpoint parse(String address, int defaultPort) {
        try {
            URI uri = URI.create("tcp://" + address.trim());
            String host = uri.getHost();
            if (host == null || uri.getUserInfo() != null || !uri.getRawPath().isEmpty() ||
                    uri.getRawQuery() != null || uri.getRawFragment() != null ||
                    uri.getRawAuthority().endsWith(":")) return null;
            int port = uri.getPort() == -1 ? defaultPort : uri.getPort();
            if (port < 1 || port > 65535) return null;
            return new Endpoint(host, port);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
