package ru.ludwigandreas.security.authn.mtls;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses Envoy's {@code x-forwarded-client-cert} header.
 *
 * <p>The format looks like a simple key/value list and is not:
 *
 * <pre>
 * By=spiffe://mesh/ns/edge/sa/gateway;Hash=468ed3...;Subject="/C=DE/O=ACME
 * GmbH/CN=acme-partner";URI=spiffe://partners/acme
 * </pre>
 *
 * <p>Values may be double-quoted, a quoted value may contain the {@code ;} and {@code ,} that
 * otherwise separate pairs and chain elements, and quotes may be backslash-escaped. Splitting on the
 * separators with {@code String.split} therefore mangles any certificate whose subject DN contains a
 * comma - which is to say, essentially all of them. Hence the character scan below.
 *
 * <p>Repeated {@code URI}/{@code DNS} keys accumulate; other repeats keep the first occurrence, so a
 * spoofed duplicate appended after the genuine value cannot override it.
 */
public final class XfccParser {

    private static final String KEY_BY = "by";
    private static final String KEY_HASH = "hash";
    private static final String KEY_SUBJECT = "subject";
    private static final String KEY_URI = "uri";
    private static final String KEY_DNS = "dns";

    private XfccParser() {
    }

    /**
     * @return one entry per certificate the header describes, in header order. With Envoy's
     *         {@code forward_client_cert_details: SANITIZE_SET} - the configuration this module
     *         expects - there is exactly one, and it is the client's.
     */
    public static List<ClientCertificateDetails> parse(String header) {
        List<ClientCertificateDetails> parsed = new ArrayList<>();
        if (header == null || header.isBlank()) {
            return parsed;
        }
        for (String element : splitTopLevel(header, ',')) {
            ClientCertificateDetails details = parseElement(element);
            if (details != null) {
                parsed.add(details);
            }
        }
        return parsed;
    }

    private static ClientCertificateDetails parseElement(String element) {
        ClientCertificateDetails.ClientCertificateDetailsBuilder builder = ClientCertificateDetails.builder();
        boolean any = false;

        for (String pair : splitTopLevel(element, ';')) {
            int separator = pair.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = pair.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String value = unquote(pair.substring(separator + 1).trim());
            if (value.isEmpty()) {
                continue;
            }
            any = true;
            switch (key) {
                case KEY_BY -> builder.by(value);
                case KEY_HASH -> builder.hash(value);
                case KEY_SUBJECT -> builder.subjectDn(value);
                case KEY_URI -> builder.uriSan(value);
                case KEY_DNS -> builder.dnsSan(value);
                // Cert/Chain carry the URL-encoded PEM. Deliberately ignored: the service does not
                // re-verify what the proxy already verified, and keeping them out of the principal
                // keeps kilobytes of certificate out of every log line and audit record.
                default -> { }
            }
        }
        return any ? builder.build() : null;
    }

    private static List<String> splitTopLevel(String input, char separator) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder(input.length());
        boolean inQuotes = false;
        boolean escaped = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
            } else if (c == '\\') {
                current.append(c);
                escaped = true;
            } else if (c == '"') {
                inQuotes = !inQuotes;
                current.append(c);
            } else if (c == separator && !inQuotes) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            return value.substring(1, value.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return value;
    }
}
