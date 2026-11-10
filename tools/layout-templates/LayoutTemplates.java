import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Draws the designers' templates from layouts.tsv (exported by the app's LayoutTemplateExport test):
 *   <ratio>/<TYPE>_gabarit.png  the sheet at print resolution with the photo zones, the title zone and the margins;
 *   <ratio>/<TYPE>_masque.png   a white sheet with transparent holes where the photos go;
 *   <ratio>/gabarits.pdf        one vector page per layout at the real 10x15 cm size.
 *
 * java tools/layout-templates/LayoutTemplates.java tools/layout-templates/layouts.tsv docs/layout-templates
 */
public class LayoutTemplates {
    static final int DPI = 300;
    /** The printer may trim up to about 2 mm: nothing important should sit closer to the edge. */
    static final int EDGE = (int) Math.round(2.0 / 25.4 * DPI);

    record Zone(int x, int y, int w, int h, int shot) {}
    record Layout(String ratio, String type, String title, int width, int height, Zone safe, List<Zone> photos) {
        boolean strips() { return type.startsWith("STRIP_"); }
    }

    public static void main(String[] args) throws IOException {
        System.setProperty("java.awt.headless", "true");
        List<Layout> layouts = new ArrayList<>();
        for (String line : Files.readAllLines(new File(args[0]).toPath(), StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            String[] f = line.split("\t");
            int[] s = ints(f[5]);
            List<Zone> photos = new ArrayList<>();
            for (String p : f[6].split(";")) {
                int[] v = ints(p);
                photos.add(new Zone(v[0], v[1], v[2], v[3], v[4]));
            }
            layouts.add(new Layout(f[0], f[1], f[2], Integer.parseInt(f[3]), Integer.parseInt(f[4]),
                new Zone(s[0], s[1], s[2], s[3], 0), photos));
        }
        Map<String, List<Layout>> byRatio = new LinkedHashMap<>();
        for (Layout l : layouts) byRatio.computeIfAbsent(l.ratio(), k -> new ArrayList<>()).add(l);
        for (var e : byRatio.entrySet()) {
            File dir = new File(args[1], "photos-" + e.getKey().replace(':', '-'));
            dir.mkdirs();
            Pdf pdf = new Pdf();
            for (Layout l : e.getValue()) {
                ImageIO.write(guide(l), "png", new File(dir, l.type() + "_gabarit.png"));
                ImageIO.write(mask(l), "png", new File(dir, l.type() + "_masque.png"));
                pdf.page(l);
            }
            Files.write(new File(dir, "gabarits.pdf").toPath(), pdf.bytes());
            System.out.println(dir + ": " + e.getValue().size() + " layouts");
        }
    }

    static int[] ints(String csv) {
        String[] p = csv.split(",");
        int[] out = new int[p.length];
        for (int i = 0; i < p.length; i++) out[i] = Integer.parseInt(p[i].trim());
        return out;
    }

    static final Color PHOTO_FILL = new Color(255, 46, 126, 70);
    static final Color PHOTO_LINE = new Color(220, 20, 100);
    static final Color ZONE_LINE = new Color(0, 73, 168);
    static final Color EDGE_LINE = new Color(230, 40, 40);

    static BufferedImage guide(Layout l) {
        BufferedImage img = new BufferedImage(l.width(), l.height(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, l.width(), l.height());

        BasicStroke dash = new BasicStroke(4f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {18f, 12f}, 0f);
        Zone z = l.safe();
        g.setColor(new Color(0, 73, 168, 18));
        g.fillRect(z.x(), z.y(), z.w(), z.h());
        g.setColor(ZONE_LINE);
        g.setStroke(dash);
        g.drawRect(z.x() + EDGE, z.y() + EDGE, z.w() - 2 * EDGE, z.h() - 2 * EDGE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 34));
        if (z.h() < 220) {
            // A slim band: one line, raised above the instructions written along the bottom edge.
            centered(g, "ZONE TITRE / LOGO · " + z.w() + " × " + z.h() + " px", z.x() + z.w() / 2, z.y() + z.h() / 2 - 22);
        } else {
            centered(g, "ZONE TITRE / LOGO", z.x() + z.w() / 2, z.y() + z.h() / 2 - 12);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 24));
            centered(g, z.w() + " × " + z.h() + " px", z.x() + z.w() / 2, z.y() + z.h() / 2 + 26);
        }

        for (Zone p : l.photos()) {
            g.setColor(PHOTO_FILL);
            g.fillRect(p.x(), p.y(), p.w(), p.h());
            g.setColor(PHOTO_LINE);
            g.setStroke(new BasicStroke(4f));
            g.drawRect(p.x(), p.y(), p.w(), p.h());
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, clamp(p.h() / 7, 28, 90)));
            centered(g, "PHOTO " + p.shot(), p.x() + p.w() / 2, p.y() + p.h() / 2);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, clamp(p.h() / 16, 18, 34)));
            centered(g, p.w() + " × " + p.h() + " px · x " + p.x() + ", y " + p.y(), p.x() + p.w() / 2,
                p.y() + p.h() / 2 + clamp(p.h() / 9, 30, 70));
        }

        if (l.strips()) {
            g.setColor(new Color(60, 60, 60));
            g.setStroke(dash);
            g.drawLine(l.width() / 2, 0, l.width() / 2, l.height());
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
            g.drawString("coupe", l.width() / 2 + 10, l.height() - EDGE - 50);
        }

        g.setColor(EDGE_LINE);
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {10f, 10f}, 0f));
        g.drawRect(EDGE, EDGE, l.width() - 2 * EDGE, l.height() - 2 * EDGE);

        label(g, l.title() + " · " + l.width() + "×" + l.height() + " px · 10×15 cm · 300 dpi · photos " + l.ratio(),
            new Font(Font.SANS_SERIF, Font.BOLD, 22), EDGE + 6, EDGE + 6, true);
        label(g, "Zones roses : recouvertes par les photos · rien d'important hors du cadre rouge · export PNG "
            + l.width() + "×" + l.height(), new Font(Font.SANS_SERIF, Font.PLAIN, 18), EDGE + 6, l.height() - EDGE - 6, false);
        g.dispose();
        return img;
    }

    static BufferedImage mask(Layout l) {
        BufferedImage img = new BufferedImage(l.width(), l.height(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, l.width(), l.height());
        g.setComposite(AlphaComposite.Clear);
        for (Zone p : l.photos()) g.fillRect(p.x(), p.y(), p.w(), p.h());
        g.dispose();
        return img;
    }

    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Text on a light box so it stays readable over the photo zones; anchored at its top or bottom-left corner. */
    static void label(Graphics2D g, String text, Font font, int x, int y, boolean top) {
        g.setFont(font);
        var m = g.getFontMetrics();
        int w = m.stringWidth(text) + 16, h = m.getHeight() + 8;
        int boxY = top ? y : y - h;
        g.setColor(new Color(255, 255, 255, 225));
        g.fillRect(x, boxY, w, h);
        g.setColor(new Color(40, 40, 40));
        g.drawString(text, x + 8, boxY + 4 + m.getAscent());
    }

    static void centered(Graphics2D g, String text, int cx, int cy) {
        var m = g.getFontMetrics();
        g.drawString(text, cx - m.stringWidth(text) / 2, cy + m.getAscent() / 2 - 4);
    }

    /** Minimal vector PDF: rectangles, dashed lines and Helvetica text (WinAnsi, which covers French accents). */
    static class Pdf {
        final List<double[]> sizes = new ArrayList<>();
        final List<String> contents = new ArrayList<>();

        static String f(double v) { return String.format(Locale.US, "%.2f", v); }

        void page(Layout l) {
            double k = 72.0 / DPI;
            double pw = l.width() * k, ph = l.height() * k;
            StringBuilder sb = new StringBuilder();
            sb.append("1 1 1 rg 0 0 ").append(f(pw)).append(' ').append(f(ph)).append(" re f\n");
            Zone z = l.safe();
            sb.append("0.92 0.95 1 rg ").append(rect(z.x() * k, ph - (z.y() + z.h()) * k, z.w() * k, z.h() * k)).append(" f\n");
            sb.append("0 0.29 0.66 RG 0.8 w [3 2] 0 d ")
                .append(rect((z.x() + EDGE) * k, ph - (z.y() + z.h() - EDGE) * k, (z.w() - 2 * EDGE) * k, (z.h() - 2 * EDGE) * k))
                .append(" S [] 0 d\n");
            text(sb, "0 0.29 0.66", "F2", 7, (z.x() + EDGE + 10) * k, ph - (z.y() + EDGE + 34) * k,
                "ZONE TITRE / LOGO  " + z.w() + "x" + z.h() + " px");
            for (Zone p : l.photos()) {
                sb.append("1 0.82 0.89 rg 0.86 0.08 0.39 RG 0.8 w ")
                    .append(rect(p.x() * k, ph - (p.y() + p.h()) * k, p.w() * k, p.h() * k)).append(" B\n");
                text(sb, "0.86 0.08 0.39", "F2", 9, (p.x() + 18) * k, ph - (p.y() + 56) * k, "PHOTO " + p.shot());
                text(sb, "0.86 0.08 0.39", "F1", 6, (p.x() + 18) * k, ph - (p.y() + 96) * k,
                    p.w() + "x" + p.h() + " px  (x " + p.x() + ", y " + p.y() + ")");
            }
            if (l.strips()) {
                sb.append("0.3 0.3 0.3 RG 0.6 w [4 3] 0 d ").append(f(pw / 2)).append(" 0 m ")
                    .append(f(pw / 2)).append(' ').append(f(ph)).append(" l S [] 0 d\n");
            }
            sb.append("0.9 0.15 0.15 RG 0.6 w [3 3] 0 d ")
                .append(rect(EDGE * k, EDGE * k, pw - 2 * EDGE * k, ph - 2 * EDGE * k)).append(" S [] 0 d\n");
            text(sb, "0.15 0.15 0.15", "F2", 6.5, (EDGE + 10) * k, ph - (EDGE + 30) * k,
                l.title() + " - " + l.width() + "x" + l.height() + " px - 10x15 cm - photos " + l.ratio());
            sizes.add(new double[] {pw, ph});
            contents.add(sb.toString());
        }

        static String rect(double x, double y, double w, double h) {
            return f(x) + " " + f(y) + " " + f(w) + " " + f(h) + " re";
        }

        static void text(StringBuilder sb, String rgb, String font, double size, double x, double y, String s) {
            String esc = s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
            sb.append(rgb).append(" rg BT /").append(font).append(' ').append(f(size)).append(" Tf ")
                .append(f(x)).append(' ').append(f(y)).append(" Td (").append(esc).append(") Tj ET\n");
        }

        byte[] bytes() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            List<Integer> offsets = new ArrayList<>();
            out.write("%PDF-1.4\n%âãÏÓ\n".getBytes(StandardCharsets.ISO_8859_1));
            int n = contents.size();
            StringBuilder kids = new StringBuilder();
            for (int i = 0; i < n; i++) kids.append(5 + 2 * i).append(" 0 R ");
            obj(out, offsets, "<< /Type /Catalog /Pages 2 0 R >>".getBytes());
            obj(out, offsets, ("<< /Type /Pages /Kids [" + kids + "] /Count " + n + " >>").getBytes());
            obj(out, offsets, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>".getBytes());
            obj(out, offsets, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>".getBytes());
            for (int i = 0; i < n; i++) {
                double[] s = sizes.get(i);
                obj(out, offsets, ("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + f(s[0]) + " " + f(s[1]) + "] "
                    + "/Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents " + (6 + 2 * i) + " 0 R >>").getBytes());
                byte[] stream = contents.get(i).getBytes(StandardCharsets.ISO_8859_1);
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                body.write(("<< /Length " + stream.length + " >>\nstream\n").getBytes());
                body.write(stream);
                body.write("\nendstream".getBytes());
                obj(out, offsets, body.toByteArray());
            }
            int xref = out.size();
            out.write(("xref\n0 " + (offsets.size() + 1) + "\n0000000000 65535 f \n").getBytes());
            for (int off : offsets) out.write(String.format("%010d 00000 n \n", off).getBytes());
            out.write(("trailer\n<< /Size " + (offsets.size() + 1) + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n").getBytes());
            return out.toByteArray();
        }

        static void obj(ByteArrayOutputStream out, List<Integer> offsets, byte[] body) throws IOException {
            offsets.add(out.size());
            out.write((offsets.size() + " 0 obj\n").getBytes());
            out.write(body);
            out.write("\nendobj\n".getBytes());
        }
    }
}
