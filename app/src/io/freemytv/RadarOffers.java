package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

/**
 * Isolated, read-only, fail-closed advertising feed for TVClean.
 * Never handles affiliate credentials; user-visible links are TVClean HTTPS redirects.
 * Runs HTTP and image decoding off the Android UI thread.
 */
final class RadarOffers {
    static final String ENDPOINT = "https://midia.ofertanoradar.cloud/tv/api/v1/offers/current";
    private static final String CLICK_PREFIX = "https://midia.ofertanoradar.cloud/tv/oferta/";
    private static final long ROTATE_MS = 600_000L;
    private static final long RETRY_MS = 120_000L;
    private static final long REFRESH_MS = 60_000L;
    private static final long PRICE_FRESH_MS = 15 * 60_000L;
    private static final int MAX_FEED_BYTES = 64 * 1024;
    private static final int MAX_IMAGE_BYTES = 850 * 1024;

    static final class Offer {
        final String id, title, store, price, imageUrl, clickUrl, disclosure;
        final boolean demo;
        final long expires, verified;
        Bitmap image;

        Offer(String id, String title, String store, String price, String imageUrl,
              String clickUrl, String disclosure, boolean demo, long expires, long verified) {
            this.id = id;
            this.title = title;
            this.store = store;
            this.price = price;
            this.imageUrl = imageUrl;
            this.clickUrl = clickUrl;
            this.disclosure = disclosure;
            this.demo = demo;
            this.expires = expires;
            this.verified = verified;
        }

        boolean valid(long now) {
            if (demo) return expires == 0 || now < expires;
            return expires > now && verified > 0 && verified <= now + 60_000L
                    && now - verified <= PRICE_FRESH_MS && clickUrl != null && price != null;
        }
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable changed;
    private List<Offer> offers = new ArrayList<>();
    private int active;
    private long fetchAt, switchAt;
    private boolean fetching, closed, frozen;
    private List<Offer> pending;

    RadarOffers(Runnable changed) { this.changed = changed; }

    Offer current() {
        if (offers.isEmpty()) return null;
        Offer o = offers.get(active);
        return o.valid(System.currentTimeMillis()) ? o : null;
    }

    /**
     * Called every 15 seconds on main thread.
     * Freeze the displayed offer when the focus is on the third card or its QR dialog is open.
     */
    void setFrozen(boolean value) { frozen = value; }

    void tick(boolean frozen) {
        if (closed) return;
        this.frozen = frozen;
        long now = System.currentTimeMillis();
        if (!frozen && pending != null) {
            List<Offer> next = pending;
            pending = null;
            apply(next);
        }
        if (!frozen && offers.size() > 1 && now - switchAt >= ROTATE_MS) {
            for (int n = 0; n < offers.size(); n++) {
                active = (active + 1) % offers.size();
                if (offers.get(active).valid(now)) break;
            }
            switchAt = now;
            changed.run();
            fetchImage();
        }
        if (!fetching && (fetchAt == 0 || now - fetchAt >= (offers.isEmpty() ? RETRY_MS : REFRESH_MS))) {
            fetching = true;
            fetchAt = now;
            worker.execute(() -> {
                List<Offer> next = null;
                try {
                    byte[] bytes = request(ENDPOINT, MAX_FEED_BYTES);
                    JSONObject root = new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                    if (root.optInt("schema_version") != 1) throw new IllegalArgumentException("schema");
                    JSONArray a = root.optJSONArray("offers");
                    if (a == null || a.length() > 10) throw new IllegalArgumentException("offers");
                    next = new ArrayList<>();
                    for (int i = 0; i < a.length(); i++) {
                        Offer candidate = parse(a.getJSONObject(i));
                        if (candidate != null && candidate.valid(System.currentTimeMillis())) next.add(candidate);
                    }
                } catch (Exception ignored) {
                    // Offline/invalid response must never crash the TV launcher.
                }
                final List<Offer> result = next;
                main.post(() -> {
                    if (closed) return;
                    fetching = false;
                    if (result == null) { changed.run(); return; }
                    if (frozen) pending = result;
                    else apply(result);
                });
            });
        }
    }

    private void apply(List<Offer> next) {
        Offer was = current();
        String selected = was == null ? null : was.id;
        for (Offer candidate : next) for (Offer previous : offers) {
            if (candidate.id.equals(previous.id)) {
                candidate.image = previous.image;
                break;
            }
        }
        offers = next;
        active = 0;
        if (selected != null) for (int i = 0; i < offers.size(); i++) {
            if (offers.get(i).id.equals(selected)) { active = i; break; }
        }
        if (switchAt == 0 || current() == null || selected == null
                || !selected.equals(current().id)) switchAt = System.currentTimeMillis();
        changed.run();
        fetchImage();
    }

    void close() {
        closed = true;
        worker.shutdownNow();
    }

    private void fetchImage() {
        Offer o = current();
        if (o == null || o.imageUrl == null || o.image != null || closed) return;
        worker.execute(() -> {
            Bitmap b = null;
            try {
                byte[] data = request(o.imageUrl, MAX_IMAGE_BYTES);
                BitmapFactory.Options size = new BitmapFactory.Options();
                size.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, size);
                if (size.outWidth > 0 && size.outHeight > 0
                        && size.outWidth <= 4096 && size.outHeight <= 4096) {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = Math.max(1, Math.max(size.outWidth, size.outHeight) / 720);
                    b = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
                }
            } catch (Exception ignored) {}
            final Bitmap loaded = b;
            main.post(() -> {
                if (closed || loaded == null) return;
                o.image = loaded;
                if (current() == o) changed.run();
            });
        });
    }

    private static Offer parse(JSONObject o) {
        String id = safe(o.optString("id"), 90);
        String title = safe(o.optString("title"), 140);
        String store = safe(o.optString("store"), 50);
        String disclosure = safe(o.optString("disclosure"), 100);
        String kind = o.optString("kind");
        if (id == null || title == null || store == null || disclosure == null
                || !disclosure.toLowerCase(java.util.Locale.ROOT).contains("publicit"))
            return null;
        boolean demo = "demo".equals(kind);
        if (!demo && !"affiliate".equals(kind)) return null;
        String price = safe(o.optString("price_brl", ""), 40);
        if (price != null && !price.matches("R\\$ [0-9.]{1,12},[0-9]{2}")) price = null;
        String clickUrl = o.optString("click_url", "");
        if (demo) { clickUrl = null; price = null; }
        else if (!clickUrl.startsWith(CLICK_PREFIX) || clickUrl.length() > 200 || !safeHttps(clickUrl, "midia.ofertanoradar.cloud"))
            return null;
        String imageUrl = o.optString("image_url", "");
        if (!safeHttps(imageUrl, "midia.ofertanoradar.cloud")) imageUrl = null;
        long expires = utc(o.optString("expires_at", ""));
        long verified = utc(o.optString("price_verified_at", ""));
        return new Offer(id, title, store, price, imageUrl, clickUrl, disclosure, demo, expires, verified);
    }

    private static String safe(String s, int max) {
        if (s == null) return null;
        s = s.replaceAll("[\\p{Cntrl}]", "").trim();
        return s.isEmpty() || s.length() > max ? null : s;
    }

    private static long utc(String iso) {
        try { return Instant.parse(iso).toEpochMilli(); }
        catch (Exception ignored) { return 0; }
    }

    private static boolean safeHttps(String url, String domain) {
        if (url == null || url.length() > 300) return false;
        try {
            URL u = new URL(url);
            return "https".equals(u.getProtocol()) && domain.equalsIgnoreCase(u.getHost())
                    && u.getUserInfo() == null && (u.getPort() == -1 || u.getPort() == 443);
        } catch (Exception e) { return false; }
    }

    private static byte[] request(String url, int limit) throws Exception {
        HttpsURLConnection c = (HttpsURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(4500);
            c.setReadTimeout(4500);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Accept", "application/json,image/webp,image/jpeg,image/png");
            if (c.getResponseCode() != 200 || c.getContentLength() > limit)
                throw new IllegalArgumentException("response");
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] b = new byte[4096]; int n, size = 0;
                while ((n = in.read(b)) != -1) {
                    size += n;
                    if (size > limit) throw new IllegalArgumentException("size");
                    out.write(b, 0, n);
                }
                return out.toByteArray();
            }
        } finally { c.disconnect(); }
    }
}
