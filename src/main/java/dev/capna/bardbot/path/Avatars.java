package dev.capna.bardbot.path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The one place in this bot that fetches anything.
 *
 * <p>Everything else hands Discord a URL and lets Discord fetch it. The Path card cannot do that,
 * because the picture has to be drawn into the image before it is uploaded, so the bytes have to
 * come here first. {@code NoNetworkTest} names this class as the single exception and fails the
 * build if any other class reaches for the network.
 *
 * <p>That matters more than it looks. A character image is a URL a Bard typed into their own
 * profile, so without the checks below, anybody who can run {@code /profile edit identity} could
 * point the bot at an address inside whatever network it runs on and read the result back. So:
 *
 * <ul>
 *   <li>https only, on the default port</li>
 *   <li>the host must be one of a handful known to serve images, listed here</li>
 *   <li>redirects are never followed, since a permitted host could otherwise forward anywhere</li>
 *   <li>the address it resolves to must be a public one, which closes the case of a permitted
 *       name that has been pointed somewhere private</li>
 *   <li>a timeout, a size cap, and the bytes must actually decode as an image</li>
 * </ul>
 *
 * <p>Nothing here ever throws at a caller. A picture that cannot be had is {@link Optional#empty()}
 * and the card draws a plain tile instead, because a Bard with an odd profile link should still get
 * their card.
 */
public final class Avatars {

    private static final Logger LOG = LoggerFactory.getLogger(Avatars.class);

    /**
     * Where a picture may come from.
     *
     * <p>Discord's own, which is where every avatar and every image posted in the guild lives, and
     * the two image hosts the help text tells people to use. A Bard who hosts their picture
     * somewhere else still gets it on their profile, where Discord does the fetching; it is only
     * the card that falls back.
     */
    private static final Set<String> ALLOWED_HOSTS = Set.of(
            "cdn.discordapp.com",
            "media.discordapp.net",
            "i.imgur.com",
            "imgur.com");

    /** Long enough for a CDN, short enough that a slash command still answers. */
    private static final Duration TIMEOUT = Duration.ofSeconds(4);

    /** An avatar is tens of kilobytes. Anything of this size is not one. */
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    /** Faces change rarely and the same ones recur, so they are kept for a while. */
    private static final Duration CACHE_FOR = Duration.ofHours(6);
    private static final int CACHE_SIZE = 256;

    private final HttpClient client;
    private final Map<String, Cached> cache;

    public Avatars() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                // Never. A permitted host that redirects is a permitted host pointing anywhere.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
                return size() > CACHE_SIZE;
            }
        };
    }

    private record Cached(Optional<BufferedImage> image, Instant at) {
    }

    /**
     * The first of these pictures that can be had.
     *
     * <p>The Bard's own character image first, since that is the one they chose, then whatever
     * Discord has for them. Empty when neither works, and the caller draws something plain.
     */
    public Optional<BufferedImage> firstOf(Optional<String> profileImage, String discordAvatar) {
        Optional<BufferedImage> chosen = profileImage.flatMap(this::fetch);
        return chosen.isPresent() ? chosen : fetch(discordAvatar);
    }

    /** @return the image at this URL, or empty if it is not allowed, not reachable, or not an image */
    public Optional<BufferedImage> fetch(String url) {
        Cached hit = hit(url);
        if (hit != null) {
            return hit.image();
        }
        Optional<BufferedImage> image = read(url);
        synchronized (cache) {
            cache.put(url, new Cached(image, Instant.now()));
        }
        return image;
    }

    private Cached hit(String url) {
        synchronized (cache) {
            Cached cached = cache.get(url);
            if (cached == null) {
                return null;
            }
            if (Duration.between(cached.at(), Instant.now()).compareTo(CACHE_FOR) > 0) {
                cache.remove(url);
                return null;
            }
            return cached;
        }
    }

    private Optional<BufferedImage> read(String url) {
        Optional<URI> permitted = permitted(url);
        if (permitted.isEmpty()) {
            return Optional.empty();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(permitted.get())
                    .timeout(TIMEOUT)
                    .header("Accept", "image/*")
                    .GET()
                    .build();
            HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    LOG.debug("Picture at {} answered {}", url, response.statusCode());
                    return Optional.empty();
                }
                byte[] bytes = body.readNBytes(MAX_BYTES);
                // Decoding is the real check. A content type can say anything.
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
                if (image == null) {
                    LOG.debug("Picture at {} did not decode as an image", url);
                    return Optional.empty();
                }
                return Optional.of(image);
            }
        } catch (IOException e) {
            LOG.debug("Could not fetch the picture at {}: {}", url, e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * Whether this URL may be fetched at all, which is the whole of the security question.
     *
     * <p>Package private so the rules can be tested without a network, which is the only way to be
     * sure they still hold. Every refusal is silent to the Bard: their card simply has a plain
     * tile on it, and the reason is in the console rather than in a reply that teaches somebody
     * what the bot will and will not fetch.
     */
    static Optional<URI> permitted(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return Optional.empty();
        }
        // Credentials in a URL are a redirect trick far more often than they are a real login.
        if (uri.getUserInfo() != null) {
            return Optional.empty();
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            return Optional.empty();
        }
        String host = uri.getHost();
        if (host == null || !ALLOWED_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        return isPublic(host) ? Optional.of(uri) : Optional.empty();
    }

    /**
     * Whether a name resolves somewhere on the public internet.
     *
     * <p>Belt and braces behind the host list: a permitted name whose DNS has been pointed at a
     * private address is the one way the list on its own could be walked around.
     */
    private static boolean isPublic(String host) {
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) {
                    LOG.warn("{} resolves to {}, which is not a public address; not fetching",
                            host, address.getHostAddress());
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
