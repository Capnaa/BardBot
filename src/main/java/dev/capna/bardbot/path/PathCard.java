package dev.capna.bardbot.path;

import dev.capna.bardbot.model.PathReward;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;

/**
 * The Path drawn as a picture: a Bard walking a dirt track towards a dandelion.
 *
 * <p>A ladder of seven numbers is a table, and a table is what {@code /path view} already shows.
 * This exists because the thing being sold is a month of trying, and a figure standing two steps
 * from the next marker says that in a way a tick in a column does not.
 *
 * <p>The figure stands between markers, not only on them. Position is interpolated along the
 * track, so an award moves them a little even when it unlocks nothing, which is the whole point of
 * drawing it.
 *
 * <p>Everything is rendered from a bundled background, so the card works with no network at all.
 * Only the face comes from outside, through {@link Avatars}, and a face that cannot be had is a
 * plain tile rather than a failure.
 */
public final class PathCard {

    private static final Logger LOG = LoggerFactory.getLogger(PathCard.class);

    private static final String TRACK = "/path/path.png";
    private static final String SUPERSTAR = "/path/superstar.jpg";

    /**
     * Where each marker sits on the track, in the background's own pixels.
     *
     * <p>Read off the image rather than guessed, and paired with {@link PathReward} in order. If
     * the background is ever redrawn these move with it, which is why they are one table here
     * rather than scattered through the drawing code.
     */
    private static final int[][] MARKERS = {
            {225, 279},   // 10
            {325, 199},   // 25
            {420, 276},   // 35
            {520, 196},   // 50
            {615, 275},   // 60
            {715, 195},   // 70
            {852, 266}};  // 80, where the track meets the dandelion

    /** Where a Bard with nothing yet is standing. */
    private static final int[] START = {72, 200};

    private static final Color GOLD = new Color(0xFF, 0xD4, 0x3B);
    private static final Color UNREACHED = new Color(0x2A, 0x3A, 0x1E);
    private static final int FACE = 78;

    private final Avatars avatars;

    public PathCard(Avatars avatars) {
        this.avatars = Objects.requireNonNull(avatars, "avatars");
    }

    /**
     * Draws one Bard's month.
     *
     * <p>Returns PNG bytes rather than a file, since the only thing that ever happens to a card is
     * being uploaded, and a bot that writes images to disk is a bot that fills a disk.
     *
     * @param name         shown on the card, already safe to draw as it is never parsed
     * @param earned       virtue since the first, which is the whole of the position
     * @param profileImage the Bard's character image, tried first
     * @param discordAvatar their Discord avatar, used when the first is absent or unreachable
     */
    public Optional<byte[]> render(String name, int earned, Optional<String> profileImage,
                                   String discordAvatar) {
        boolean finished = earned >= PathReward.FREE_PASSAGE.threshold();
        Optional<BufferedImage> background = background(finished ? SUPERSTAR : TRACK);
        if (background.isEmpty()) {
            return Optional.empty();
        }

        BufferedImage face = avatars.firstOf(profileImage, discordAvatar).orElse(null);
        BufferedImage card = background.get();
        Graphics2D g = card.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            if (finished) {
                superstar(g, face, name, earned);
            } else {
                track(g, face, name, earned);
            }
        } finally {
            g.dispose();
        }
        return png(card);
    }

    private void track(Graphics2D g, BufferedImage face, String name, int earned) {
        // Numbers sit below the track whether the ground rises or falls, because the Bard stands
        // on top of it and would otherwise cover the one marker they are standing on.
        PathReward[] rewards = PathReward.values();
        for (int i = 0; i < rewards.length && i < MARKERS.length; i++) {
            boolean reached = rewards[i].metBy(earned);
            label(g, String.valueOf(rewards[i].threshold()),
                    MARKERS[i][0], MARKERS[i][1] + 50,
                    reached ? GOLD : UNREACHED, reached ? 40 : 34);
        }

        int[] standing = standing(earned);
        drawFace(g, face, name, standing[0], standing[1], false);
        banner(g, name, earned);
    }

    /** The end card. The dancer is already on it; this adds whose night it is. */
    private void superstar(Graphics2D g, BufferedImage face, String name, int earned) {
        drawFace(g, face, name, 118, 700, true);
        label(g, fit(g, name, 200), 118, 744, GOLD, 32);
        label(g, earned + " virtue", 118, 773, Color.WHITE, 21);
    }

    /**
     * Where the figure stands for a score.
     *
     * <p>Interpolated between the markers either side, so progress shows continuously rather than
     * in seven jumps. Walking the segments in order also means the figure follows the bends in the
     * track instead of cutting across the grass.
     */
    static int[] standing(int earned) {
        PathReward[] rewards = PathReward.values();
        if (earned <= 0) {
            return new int[]{START[0], START[1]};
        }
        double previousX = START[0];
        double previousY = START[1];
        int previousThreshold = 0;

        for (int i = 0; i < rewards.length && i < MARKERS.length; i++) {
            int threshold = rewards[i].threshold();
            if (earned < threshold) {
                double progress = (earned - previousThreshold)
                        / (double) (threshold - previousThreshold);
                return new int[]{
                        (int) Math.round(previousX + (MARKERS[i][0] - previousX) * progress),
                        (int) Math.round(previousY + (MARKERS[i][1] - previousY) * progress)};
            }
            previousThreshold = threshold;
            previousX = MARKERS[i][0];
            previousY = MARKERS[i][1];
        }
        return new int[]{(int) previousX, (int) previousY};
    }

    /**
     * The Bard, standing on the ground rather than sunk into it.
     *
     * <p>Square and hard edged, drawn without smoothing, because a Minecraft face scaled up with
     * interpolation stops looking like one.
     */
    private void drawFace(Graphics2D g, BufferedImage face, String name, int cx, int groundY,
                          boolean crowned) {
        int x = cx - FACE / 2;
        int y = groundY - FACE - 16;

        g.setColor(new Color(0, 0, 0, 90));
        g.fill(new Ellipse2D.Double(cx - 28, groundY - 14, 56, 14));

        g.setColor(crowned ? GOLD : new Color(0x1C, 0x1C, 0x1C));
        g.fillRoundRect(x - 6, y - 6, FACE + 12, FACE + 12, 10, 10);
        g.setColor(new Color(0, 0, 0, 160));
        g.drawRoundRect(x - 6, y - 6, FACE + 12, FACE + 12, 10, 10);

        if (face == null) {
            plainTile(g, name, x, y);
            return;
        }
        Object previous = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setComposite(AlphaComposite.SrcOver);
        g.drawImage(square(face), x, y, FACE, FACE, null);
        if (previous != null) {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, previous);
        }
    }

    /** What a Bard gets when their picture could not be had: their initial, not an error. */
    private void plainTile(Graphics2D g, String name, int x, int y) {
        g.setColor(new Color(0x4A, 0x5B, 0x34));
        g.fillRect(x, y, FACE, FACE);
        String initial = name.isBlank()
                ? "?"
                : name.strip().substring(0, 1).toUpperCase(java.util.Locale.ROOT);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 46));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(new Color(0xE8, 0xE8, 0xE8));
        g.drawString(initial, x + (FACE - fm.stringWidth(initial)) / 2,
                y + (FACE + fm.getAscent() - fm.getDescent()) / 2);
    }

    /**
     * The middle square of a picture.
     *
     * <p>A Discord avatar is square already, but a character image is whatever the Bard uploaded,
     * and stretching a tall drawing into a square tile makes everybody look wrong.
     */
    private static BufferedImage square(BufferedImage image) {
        int side = Math.min(image.getWidth(), image.getHeight());
        if (side == image.getWidth() && side == image.getHeight()) {
            return image;
        }
        return image.getSubimage((image.getWidth() - side) / 2, (image.getHeight() - side) / 2,
                side, side);
    }

    /** Who this is and how far along, so the picture still says something on its own. */
    private void banner(Graphics2D g, String name, int earned) {
        Optional<PathReward> next = java.util.Arrays.stream(PathReward.values())
                .filter(reward -> !reward.metBy(earned))
                .findFirst();

        g.setColor(new Color(0, 0, 0, 150));
        g.fillRoundRect(24, 20, 452, next.isPresent() ? 92 : 70, 14, 14);

        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
        g.setColor(GOLD);
        g.drawString(fit(g, name, 400), 44, 56);

        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
        g.setColor(Color.WHITE);
        g.drawString(earned + " virtue this month", 44, 86);

        next.ifPresent(reward -> {
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 19));
            g.setColor(new Color(0xD8, 0xD8, 0xD8));
            g.drawString("Next: " + reward.threshold() + " — " + reward.display(), 44, 107);
        });
    }

    private static String fit(Graphics2D g, String text, int width) {
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(text) <= width) {
            return text;
        }
        String cut = text;
        while (cut.length() > 1 && fm.stringWidth(cut + "…") > width) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "…";
    }

    /** Heavy black behind the glyph, so a number reads over grass as well as over dirt. */
    private static void label(Graphics2D g, String text, int cx, int baseline, Color color,
                              int size) {
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, size));
        FontMetrics fm = g.getFontMetrics();
        int x = cx - fm.stringWidth(text) / 2;

        g.setColor(new Color(0, 0, 0, 200));
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                if (dx * dx + dy * dy >= 5) {
                    g.drawString(text, x + dx, baseline + dy);
                }
            }
        }
        g.setColor(color);
        g.drawString(text, x, baseline);
    }

    /**
     * A fresh copy of a bundled background.
     *
     * <p>Read every time rather than held, because the card is drawn onto it and a cached one
     * would collect every Bard who ever ran the command. Decoding a few hundred kilobytes is far
     * cheaper than the bug that would be.
     */
    private Optional<BufferedImage> background(String resource) {
        try (InputStream in = PathCard.class.getResourceAsStream(resource)) {
            if (in == null) {
                LOG.error("The background {} is missing from the jar; no card was drawn", resource);
                return Optional.empty();
            }
            BufferedImage read = ImageIO.read(in);
            // Copied into a type with an alpha channel, since the JPEG background has none and
            // everything drawn onto it is translucent.
            BufferedImage canvas = new BufferedImage(read.getWidth(), read.getHeight(),
                    BufferedImage.TYPE_INT_RGB);
            Graphics2D g = canvas.createGraphics();
            g.drawImage(read, 0, 0, null);
            g.dispose();
            return Optional.of(canvas);
        } catch (IOException e) {
            LOG.error("Could not read the background {}", resource, e);
            return Optional.empty();
        }
    }

    private static Optional<byte[]> png(BufferedImage card) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(512 * 1024);
            ImageIO.write(card, "png", out);
            return Optional.of(out.toByteArray());
        } catch (IOException e) {
            LOG.error("Could not encode a Path card", e);
            return Optional.empty();
        }
    }
}
