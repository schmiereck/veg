package io.veg.cli.desktop;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Generates {@code logo.png} and a Windows {@code .ico} application icon from {@code pic/logo-veg-smal.jpg}
 * and prepares/finalizes the {@code jpackage} staging directory for {@code VEG-Studio.exe}.
 */
public final class WindowsIconGenerator {

    private WindowsIconGenerator() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            throw new IllegalArgumentException("Expected command: prepare | post-package | icon");
        }
        switch (args[0]) {
            case "prepare" -> prepareJPackage();
            case "post-package" -> copyBundledProjectKb();
            case "icon" -> {
                Path src = resolveExistingPath("pic/logo-veg-smal.jpg", "../pic/logo-veg-smal.jpg");
                Path outPng = args.length > 1 ? Path.of(args[1]) : Path.of("veg-cli/src/main/resources/studio/logo.png");
                Path outIco = args.length > 2 ? Path.of(args[2]) : Path.of("veg-cli/target/jpackage-icon/VEG-Studio.ico");
                BufferedImage img = renderSquareIcon(src, 256);
                writePng(img, outPng);
                writeIco(img, outIco);
            }
            default -> throw new IllegalArgumentException("Unknown WindowsIconGenerator command: " + args[0]);
        }
    }

    private static void prepareJPackage() throws IOException {
        Path logoSrc = resolveExistingPath("pic/logo-veg-smal.jpg", "../pic/logo-veg-smal.jpg");
        Path resourcesPng = Files.isDirectory(Path.of("src/main/resources"))
                ? Path.of("src/main/resources/studio/logo.png")
                : Path.of("veg-cli/src/main/resources/studio/logo.png");
        Path targetBase = Files.isDirectory(Path.of("target")) ? Path.of("target") : Path.of("veg-cli/target");

        BufferedImage img = renderSquareIcon(logoSrc, 256);
        writePng(img, resourcesPng);
        writePng(img, targetBase.resolve("classes/studio/logo.png"));
        writeIco(img, targetBase.resolve("jpackage-icon/VEG-Studio.ico"));

        Path mainJar = targetBase.resolve("veg-cli-0.1.0-SNAPSHOT.jar");
        if (!Files.isRegularFile(mainJar)) {
            throw new IllegalStateException("Expected shaded CLI JAR at " + mainJar);
        }
        Path inputDir = targetBase.resolve("jpackage-input");
        deleteRecursivelyIfExists(inputDir);
        Files.createDirectories(inputDir);
        Files.copy(mainJar, inputDir.resolve("veg-cli-0.1.0-SNAPSHOT.jar"), StandardCopyOption.REPLACE_EXISTING);

        deleteRecursivelyIfExists(targetBase.resolve("dist/VEG-Studio"));
    }

    private static void copyBundledProjectKb() throws IOException {
        Path srcKb = resolveOptionalPath("data/project-kb", "../data/project-kb");
        if (srcKb == null || !Files.isDirectory(srcKb)) {
            return;
        }
        Path targetBase = Files.isDirectory(Path.of("target")) ? Path.of("target") : Path.of("veg-cli/target");
        Path distApp = targetBase.resolve("dist/VEG-Studio");
        if (!Files.isDirectory(distApp)) {
            return;
        }
        Path destKb = distApp.resolve("data/project-kb");
        deleteRecursivelyIfExists(destKb);
        copyDirectory(srcKb, destKb);
    }

    public static BufferedImage renderSquareIcon(Path sourceImagePath, int size) throws IOException {
        Objects.requireNonNull(sourceImagePath, "sourceImagePath must not be null");
        BufferedImage raw = ImageIO.read(sourceImagePath.toFile());
        if (raw == null) {
            throw new IOException("Could not decode image file: " + sourceImagePath);
        }
        int minSide = Math.min(raw.getWidth(), raw.getHeight());
        int cropX = (raw.getWidth() - minSide) / 2;
        int cropY = (raw.getHeight() - minSide) / 2;

        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setComposite(AlphaComposite.Src);
            double arc = size * 0.16;
            g.setClip(new RoundRectangle2D.Double(0, 0, size, size, arc, arc));
            g.drawImage(raw, 0, 0, size, size, cropX, cropY, cropX + minSide, cropY + minSide, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    public static void writePng(BufferedImage image, Path outputPng) throws IOException {
        if (outputPng.toAbsolutePath().getParent() != null) {
            Files.createDirectories(outputPng.toAbsolutePath().getParent());
        }
        ImageIO.write(image, "png", outputPng.toFile());
    }

    public static void writeIco(BufferedImage square256Image, Path outputIco) throws IOException {
        if (outputIco.toAbsolutePath().getParent() != null) {
            Files.createDirectories(outputIco.toAbsolutePath().getParent());
        }
        ByteArrayOutputStream pngBuf = new ByteArrayOutputStream();
        ImageIO.write(square256Image, "png", pngBuf);
        byte[] pngBytes = pngBuf.toByteArray();

        int w = square256Image.getWidth() >= 256 ? 0 : square256Image.getWidth();
        int h = square256Image.getHeight() >= 256 ? 0 : square256Image.getHeight();

        // Standard Windows ICONDIR (6 bytes) + 1 ICONDIRENTRY (16 bytes) wrapping 256x256 PNG payload
        ByteBuffer ico = ByteBuffer.allocate(22 + pngBytes.length).order(ByteOrder.LITTLE_ENDIAN);
        ico.putShort((short) 0); // reserved
        ico.putShort((short) 1); // type = 1 (icon)
        ico.putShort((short) 1); // image count = 1
        ico.put((byte) w);
        ico.put((byte) h);
        ico.put((byte) 0); // palette colors
        ico.put((byte) 0); // reserved
        ico.putShort((short) 1); // color planes
        ico.putShort((short) 32); // bits per pixel
        ico.putInt(pngBytes.length);
        ico.putInt(22); // offset to PNG payload
        ico.put(pngBytes);

        Files.write(outputIco, ico.array());
    }

    private static Path resolveExistingPath(String primary, String fallback) {
        Path found = resolveOptionalPath(primary, fallback);
        if (found == null) {
            throw new IllegalStateException("Could not find file at " + primary + " or " + fallback);
        }
        return found;
    }

    private static Path resolveOptionalPath(String primary, String fallback) {
        Path p1 = Path.of(primary);
        if (Files.exists(p1)) {
            return p1;
        }
        Path p2 = Path.of(fallback);
        return Files.exists(p2) ? p2 : null;
    }

    private static void deleteRecursivelyIfExists(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static void copyDirectory(Path src, Path dest) throws IOException {
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path source : walk.toList()) {
                Path rel = src.relativize(source);
                Path target = dest.resolve(rel);
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    if (target.getParent() != null) {
                        Files.createDirectories(target.getParent());
                    }
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
