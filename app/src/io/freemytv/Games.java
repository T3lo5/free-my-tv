package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The games of the day: who plays, when, and where to watch it.
 *
 * Two sources. API-Football (the key in config.properties) for the football: the teams' crests, the league, its logo
 * and the country. ESPN's public scoreboard (no key) for the TV channel — it publishes the network of each game — and
 * for the sports API-Football does not serve here (American football and MMA). The list is fetched in the background
 * (a few times a day) and kept in a file, so the home screen has it right away.
 */
final class Games {
    /** One game: the teams, when it starts, and where it is on. */
    static final class Game {
        String league = "", home = "", away = "", channel = "", venue = "";
        String country = "";         // the country of the league (Brazil, England...)
        String channelCountry = "";  // the country of the channel (the United States, for most)
        String homeLogo = "", awayLogo = "";   // the teams' crests
        String leagueLogo = "";
        long at;                 // when it starts (in the TV's timezone)
        boolean live, done;
        int id;

        /** "Flamengo x Palmeiras" */
        String match() { return home + " x " + away; }

        /** The flag of the country the channel is for (the league's when the channel has none). */
        String flag() { return countryFlag(channelCountry.isEmpty() ? country : channelCountry); }

        /** "21:30" (the TV's timezone). */
        String time() {
            DateTimeFormatter f = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
            return f.format(Instant.ofEpochMilli(at));
        }
    }

    interface Listener { void onGames(); }

    private static final String ESPN = "https://site.api.espn.com/apis/site/v2/sports/";
    private static final String APISPORTS = "https://v3.football.api-sports.io/";
    /**
     * The leagues we show: API-Football's number and name, ESPN's path, the country (for the flag) and where to
     * watch it in Brazil. The number 0 means ESPN only (the sports API-Football does not serve here).
     */
    private static final String[][] LEAGUES = {
        {"201", "Brazil Serie A", "soccer/bra.1", "br", "Premiere, SporTV ou Globo"},
        {"72", "Brazil Serie B", "soccer/bra.2", "br", "Premiere ou SporTV"},
        {"73", "Copa do Brasil", "", "br", "Globo, SporTV ou Premiere"},
        {"39", "Premier League", "soccer/eng.1", "gb", ""},
        {"140", "La Liga", "soccer/esp.1", "es", ""},
        {"78", "Bundesliga", "soccer/ger.1", "de", ""},
        {"135", "Serie A", "soccer/ita.1", "it", ""},
        {"61", "Ligue 1", "soccer/fra.1", "fr", ""},
        {"94", "Primeira Liga", "soccer/por.1", "pt", ""},
        {"88", "Eredivisie", "soccer/ned.1", "nl", ""},
        {"40", "Championship", "soccer/eng.2", "gb", ""},
        {"2", "UEFA Champions League", "soccer/uefa.champions", "eu", ""},
        {"848", "UEFA Conference League", "soccer/uefa.europa.conf", "eu", ""},
        {"3", "UEFA Europa League", "soccer/uefa.europa", "eu", ""},
        {"13", "CONMEBOL Libertadores", "", "eu", ""},
        {"11", "CONMEBOL Sudamericana", "soccer/conmebol.sudamericana", "eu", ""},
        {"253", "Major League Soccer", "soccer/usa.1", "us", ""},
        {"0", "", "football/nfl", "us", ""},
        {"0", "", "football/college-football", "us", ""},
        {"0", "", "mma/ufc", "us", ""},
    };
    /** How many games of each league are kept (the NCAA has dozens on a Saturday). */
    private static final int PER_LEAGUE = 12;
    /** How often the list is asked for again. */
    private static final long FRESH = 3 * 3600_000L;
    /** Bumped when what is saved changes (the old file is thrown away). */
    private static final int VERSION = 6;   // 6: more competitions, and the channel's own country   // 5: the channel's country is the broadcaster's, not the league's
    /** Where the little flags come from (40 px wide, a few kB each). */
    private static final String FLAGS = "https://flagcdn.com/w40/";

    private static Games instance;

    static synchronized Games get(Context c) {
        if (instance == null) instance = new Games(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final List<Game> games = new ArrayList<>();
    private long fetchedAt, day;
    private boolean fetching;

    private Games(Context c) {
        context = c;
        load();
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** The games of today, in order. */
    List<Game> today() {
        synchronized (games) {
            return new ArrayList<>(games);
        }
    }

    /** Every team in the list being shown, by name: what the favourites screen offers. */
    static List<String> teams(Context c) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Game g : get(c).today()) {
            for (String team : new String[] {g.home, g.away}) {
                if (!team.isEmpty() && seen.add(team)) out.add(team);
            }
        }
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** Asks for the list when it is old (or from another day). */
    void refreshIfStale() {
        if (fetching) return;
        long now = System.currentTimeMillis();
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long today = cal.getTimeInMillis();
        boolean fresh = now - fetchedAt < FRESH && day == today;
        if (fresh && !today().isEmpty()) return;   // an empty list is tried again
        fetching = true;
        io.execute(() -> {
            try {
                fetch(today);
            } catch (Exception e) {
                AppLog.w("games: " + e.getMessage());
            } finally {
                fetching = false;
                main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onGames(); });
            }
        });
    }

    // MARK: - The network

    private void fetch(long today) throws Exception {
        // The day is the TV's: from the local midnight to the next one.
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long from = cal.getTimeInMillis();
        cal.add(Calendar.DAY_OF_YEAR, 1);
        long to = cal.getTimeInMillis();
        String[] dates = {date(from), date(to)};

        // The football: API-Football, with the crests and the league.
        List<Game> football = footballDay(from, to);

        // ESPN: the games of the same leagues (they carry the channel) and the sports API-Football does not serve
        // here (American football and MMA). A few at a time, so the list is ready sooner.
        Map<String, List<Game>> espn = new HashMap<>();
        List<Game> espnOnly = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (String[] league : LEAGUES) {
                final String[] row = league;
                if (row[2].isEmpty()) continue;   // ESPN has no path for it: the games come from API-Football only
                pool.execute(() -> {
                    List<Game> games = new ArrayList<>();
                    for (String day : dates) {
                        try {
                            games.addAll(espnLeague(row[2], row[3], day));
                        } catch (IOException e) {
                            AppLog.w("games: " + row[2] + ": " + e.getMessage());
                        }
                    }
                    synchronized (espn) {
                        espn.put(row[2], games);
                        if (row[0].equals("0")) espnOnly.addAll(games);
                    }
                });
            }
            pool.shutdown();
            pool.awaitTermination(2, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }

        // The channel of each football game: the same league, the closest time (the channel is the same for the
        // whole round, so the game itself does not have to be the same one).
        for (Game g : football) {
            String[] row = leagueOf(g);
            if (row == null || row[2].isEmpty()) continue;   // no ESPN path (Copa do Brasil, Libertadores)
            Game match = closest(g, espn.get(row[2]));
            if (match == null) continue;
            if (!match.channel.isEmpty()) {
                g.channel = match.channel;
                g.channelCountry = match.channelCountry;
            }
            // The crests: ESPN's pictures are on a host that resolves everywhere (the API's is not always).
            if (!match.homeLogo.isEmpty()) g.homeLogo = match.homeLogo;
            if (!match.awayLogo.isEmpty()) g.awayLogo = match.awayLogo;
        }

        List<Game> all = new ArrayList<>(football);
        all.addAll(espnOnly);
        all.removeIf(g -> g.at < from || g.at >= to);
        Collections.sort(all, Comparator.comparingLong(g -> g.at));
        // The teams the person follows go first; the rest of the day stays below, in time order.
        Set<String> favourites = Prefs.favorites();
        if (!favourites.isEmpty()) {
            List<Game> followed = new ArrayList<>(), rest = new ArrayList<>();
            for (Game g : all) {
                if (favourites.contains(Prefs.teamKey(g.home)) || favourites.contains(Prefs.teamKey(g.away))) followed.add(g);
                else rest.add(g);
            }
            all.clear();
            all.addAll(followed);
            all.addAll(rest);
        }
        synchronized (games) {
            games.clear();
            games.addAll(all);
        }
        fetchedAt = System.currentTimeMillis();
        day = from;
        save();
        AppLog.w("games: " + all.size() + " today");
    }

    /** The league of a game (the table's row, by its name). */
    private static String[] leagueOf(Game g) {
        for (String[] league : LEAGUES) {
            if (!league[1].isEmpty() && league[1].equals(g.league)) return league;
        }
        return null;
    }

    /** The game of the same league that starts closest to this one (the channel is the same for the whole round). */
    private static Game closest(Game g, List<Game> others) {
        Game best = null;
        long bestDiff = Long.MAX_VALUE;
        for (Game o : others) {
            if (!o.league.equals(g.league)) continue;
            long diff = Math.abs(o.at - g.at);
            if (diff < bestDiff && diff < 3 * 3600_000L) {
                bestDiff = diff;
                best = o;
            }
        }
        return best;
    }

    /** The games of the day from API-Football: the football, with the crests and the league. */
    private List<Game> footballDay(long from, long to) throws IOException {
        List<Game> out = new ArrayList<>();
        String apiKey = Keys.football(context).trim();
        if (apiKey.isEmpty()) return out;
        String tz = java.util.TimeZone.getDefault().getID();
        String url = APISPORTS + "fixtures?date=" + dashedDate(from) + "&timezone=" + tz.replace(" ", "%20");
        JSONObject json = get(url, apiKey);
        JSONArray fixtures = json.optJSONArray("response");
        if (fixtures == null) return out;
        Map<String, String[]> byId = new HashMap<>();
        for (String[] league : LEAGUES) if (!league[0].equals("0")) byId.put(league[0], league);
        for (int i = 0; i < fixtures.length(); i++) {
            JSONObject f = fixtures.optJSONObject(i);
            if (f == null) continue;
            JSONObject league = f.optJSONObject("league");
            JSONObject fixture = f.optJSONObject("fixture");
            if (league == null || fixture == null) continue;
            String[] row = byId.get(String.valueOf(league.optInt("id")));
            if (row == null) continue;
            Game g = new Game();
            g.league = row[1];
            g.country = row[3];
            g.id = fixture.optInt("id");
            g.at = parse(fixture.optString("date"));
            if (g.at <= 0) continue;
            JSONObject status = fixture.optJSONObject("status");
            String shortStatus = status == null ? "" : status.optString("short");
            g.live = "1H".equals(shortStatus) || "2H".equals(shortStatus) || "HT".equals(shortStatus)
                    || "LIVE".equalsIgnoreCase(shortStatus);
            g.done = "FT".equals(shortStatus) || "AET".equals(shortStatus) || "PEN".equals(shortStatus);
            if (g.done) continue;
            JSONObject venue = fixture.optJSONObject("venue");
            if (venue != null) g.venue = venue.optString("name");
            JSONObject teams = f.optJSONObject("teams");
            if (teams != null) {
                JSONObject home = teams.optJSONObject("home"), away = teams.optJSONObject("away");
                if (home != null) {
                    g.home = home.optString("name");
                    g.homeLogo = home.optString("logo");
                }
                if (away != null) {
                    g.away = away.optString("name");
                    g.awayLogo = away.optString("logo");
                }
            }
            if (g.home.isEmpty() || g.away.isEmpty()) continue;
            g.leagueLogo = league.optString("logo");
            if (g.channel.isEmpty() && !row[4].isEmpty()) g.channel = row[4];   // where to watch it in Brazil
            out.add(g);
        }
        return out;
    }

    /** The games of one league on one day, from ESPN (with the channel). */
    private List<Game> espnLeague(String path, String country, String date) throws IOException {
        List<Game> out = new ArrayList<>();
        JSONObject json = get(ESPN + path + "/scoreboard?dates=" + date + "&limit=" + (PER_LEAGUE * 3), null);
        JSONArray events = json.optJSONArray("events");
        if (events == null) return out;
        for (int i = 0; i < events.length() && out.size() < PER_LEAGUE; i++) {
            JSONObject e = events.optJSONObject(i);
            if (e == null) continue;
            Game g = new Game();
            g.league = espnName(path);
            g.country = country;
            g.id = e.optInt("id");
            g.at = parse(e.optString("date"));
            if (g.at <= 0) continue;
            JSONObject comp = e.optJSONArray("competitions") == null ? null
                    : e.optJSONArray("competitions").optJSONObject(0);
            if (comp == null) continue;
            JSONArray teams = comp.optJSONArray("competitors");
            if (teams != null) {
                for (int t = 0; t < teams.length(); t++) {
                    JSONObject team = teams.optJSONObject(t);
                    if (team == null) continue;
                    JSONObject info = team.optJSONObject("team");
                    String label = info == null ? "" : info.optString("displayName");
                    if ("home".equals(team.optString("homeAway"))) {
                        g.home = label;
                        if (info != null) g.homeLogo = info.optString("logo");
                    } else {
                        g.away = label;
                        if (info != null) g.awayLogo = info.optString("logo");
                    }
                }
            }
            if (g.home.isEmpty() || g.away.isEmpty()) continue;
            String state = e.optJSONObject("status") == null ? "" : e.optJSONObject("status").optJSONObject("type") == null
                    ? "" : e.optJSONObject("status").optJSONObject("type").optString("state");
            g.live = "in".equals(state);
            g.done = "post".equals(state);
            if (g.done) continue;
            JSONObject venue = comp.optJSONObject("venue");
            if (venue != null) g.venue = venue.optString("fullName");
            // The name and where it is: ESPN's `broadcasts` are its own US networks, so a name from there is a US
            // channel even when the match is in Italy (the flag used to say the league's country, which misled).
            String[] on = channel(comp);
            g.channel = on[0];
            g.channelCountry = on[1];
            out.add(g);
        }
        return out;
    }

    /** The name of the league, as we show it. */
    private static String espnName(String path) {
        for (String[] league : LEAGUES) if (league[2].equals(path)) return league[1];
        return path;
    }

    /** Where it is on, as {channel, country}: the channel ESPN publishes for the game, and where it is. */
    private static String[] channel(JSONObject comp) {
        JSONArray broadcasts = comp.optJSONArray("broadcasts");
        if (broadcasts != null) {
            for (int i = 0; i < broadcasts.length(); i++) {
                JSONObject b = broadcasts.optJSONObject(i);
                if (b == null) continue;
                JSONArray names = b.optJSONArray("names");
                if (names == null || names.length() == 0) continue;
                String name = names.optString(0);
                if (!name.isEmpty()) return new String[] {name, "us"};   // ESPN's own feed: a US network
            }
        }
        JSONArray geo = comp.optJSONArray("geoBroadcasters");
        if (geo != null) {
            for (int i = 0; i < geo.length(); i++) {
                JSONObject g = geo.optJSONObject(i);
                if (g == null || g.optJSONObject("media") == null) continue;
                String name = g.optJSONObject("media").optString("shortName");
                if (!name.isEmpty()) return new String[] {name, channelCountry(g)};
            }
        }
        return new String[] {"", ""};
    }

    /** The country of a broadcaster ("us", "br"...). */
    private static String channelCountry(JSONObject broadcaster) {
        JSONObject media = broadcaster.optJSONObject("media");
        if (media != null && media.optJSONObject("country") != null) {
            String code = media.optJSONObject("country").optString("code");
            if (!code.isEmpty()) return code.toLowerCase(Locale.ROOT);
        }
        String region = broadcaster.optString("region");
        return region.isEmpty() ? "" : region.toLowerCase(Locale.ROOT);
    }

    /** The flag of a country ("br" → the Brazilian flag). */
    static String countryFlag(String country) {
        if (country == null || country.length() != 2) return "";
        String c = country.toUpperCase(Locale.ROOT);
        if (c.charAt(0) < 'A' || c.charAt(0) > 'Z' || c.charAt(1) < 'A' || c.charAt(1) > 'Z') return "";
        return new String(new char[] {(char) (0x1F1E6 + c.charAt(0) - 'A'), (char) (0x1F1E6 + c.charAt(1) - 'A')});
    }

    /** The little flag of a country (the picture). */
    static String flagUrl(String country) {
        if (country == null || country.length() != 2) return "";
        String c = country.toLowerCase(Locale.ROOT);
        if (c.charAt(0) < 'a' || c.charAt(0) > 'z' || c.charAt(1) < 'a' || c.charAt(1) > 'z') return "";
        return FLAGS + c + ".png";
    }

    private static long parse(String date) {
        if (date == null || date.isEmpty()) return 0;
        try {
            return Instant.parse(date).toEpochMilli();
        } catch (Exception e) {
            // ESPN leaves the seconds out ("2026-10-21T16:45Z"): put them back.
            try {
                String s = date.length() == 17 ? date.substring(0, 16) + ":00" + date.substring(16) : date;
                return Instant.parse(s).toEpochMilli();
            } catch (Exception e2) {
                return 0;
            }
        }
    }

    /** "20261021" for a moment (the TV's timezone). */
    private static String date(long moment) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(moment);
        return String.format(java.util.Locale.US, "%04d%02d%02d", cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    /** "2026-10-21" for a moment (the way API-Football wants it). */
    private static String dashedDate(long moment) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(moment);
        return String.format(java.util.Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    private JSONObject get(String url, String key) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "FreeMyTV/1.0 (Android TV)");
        if (key != null) c.setRequestProperty("x-apisports-key", key);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            String body = read(c.getInputStream());
            try {
                return new JSONObject(body);
            } catch (org.json.JSONException e) {
                throw new IOException("bad answer from " + url);
            }
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }

    // MARK: - The teams' crests

    /** The crests already loaded (by their URL). */
    private static final Map<String, Bitmap> LOGOS = new HashMap<>();
    private static final Set<String> LOADING = new HashSet<>();
    private static final Set<String> FAILED = new HashSet<>();
    private static final int CREST = 56;   // the size they are drawn at

    /**
     * The team's crest (the little shield). It is scaled down and kept in the cache, so the second time it is already
     * there. Returns null while it is on its way.
     */
    static Bitmap crest(Context c, String url) {
        return image(c, url, CREST);
    }

    /** The little flag of a country. */
    static Bitmap flag(Context c, String country) {
        return image(c, flagUrl(country), 30);
    }

    /** Downloads a picture, scales it down and keeps it (in memory and on disk). */
    private static Bitmap image(Context c, String url, int size) {
        if (url == null || url.isEmpty()) return null;
        synchronized (LOGOS) {
            Bitmap b = LOGOS.get(url);
            if (b != null) return b;
        }
        File f = crestFile(c, url);
        if (f.exists()) {
            Bitmap b = BitmapFactory.decodeFile(f.getPath());
            if (b != null) {
                synchronized (LOGOS) {
                    LOGOS.put(url, b);
                }
                return b;
            }
        }
        synchronized (LOADING) {
            if (LOADING.contains(url) || FAILED.contains(url)) return null;
            LOADING.add(url);
        }
        Games games = get(c);
        games.io.execute(() -> {
            try {
                Bitmap b = download(url, size);
                if (b != null) {
                    synchronized (LOGOS) {
                        LOGOS.put(url, b);
                    }
                    save(f, b);
                    games.main.post(() -> { for (Listener l : new ArrayList<>(games.listeners)) l.onGames(); });
                }
            } catch (Exception e) {
                AppLog.w("picture: " + e.getMessage());
                synchronized (FAILED) {   // not tried again (the host may be blocked for good)
                    FAILED.add(url);
                }
            } finally {
                synchronized (LOADING) {
                    LOADING.remove(url);
                }
            }
        });
        return null;
    }

    /** Downloads the picture and scales it down to the size it is drawn at. */
    private static Bitmap download(String url, int size) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "FreeMyTV/1.0 (Android TV)");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            byte[] data = readBytes(c.getInputStream());
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (b == null) throw new IOException("not an image");
            Bitmap small = Bitmap.createScaledBitmap(b, size, size, true);
            if (small != b) b.recycle();
            return small;
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    /** The file for the crest (the last part of the URL is the team's number). */
    private static File crestFile(Context c, String url) {
        String name = url.substring(url.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return new File(new File(c.getCacheDir(), "crests"), name + ".png");
    }

    private static void save(File file, Bitmap b) {
        try {
            //noinspection ResultOfMethodCallIgnored
            file.getParentFile().mkdirs();
            try (FileOutputStream out = new FileOutputStream(file)) {
                b.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
        } catch (IOException e) {
            AppLog.w("crest not saved: " + e.getMessage());
        }
    }

    // MARK: - The file (so the list is there right away)

    private File file() {
        return new File(context.getFilesDir(), "games.json");
    }

    private void save() {
        try {
            JSONArray list = new JSONArray();
            for (Game g : today()) {
                list.put(new JSONObject().put("id", g.id).put("league", g.league).put("home", g.home)
                        .put("away", g.away).put("channel", g.channel).put("venue", g.venue)
                        .put("country", g.country).put("channelCountry", g.channelCountry)
                        .put("homeLogo", g.homeLogo).put("awayLogo", g.awayLogo).put("leagueLogo", g.leagueLogo)
                        .put("at", g.at).put("live", g.live));
            }
            JSONObject out = new JSONObject().put("version", VERSION).put("at", fetchedAt)
                    .put("day", day).put("games", list);
            File f = file();
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream os = new FileOutputStream(tmp)) {
                os.write(out.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(f)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Exception e) {
            AppLog.w("games: could not save: " + e.getMessage());
        }
    }

    private void load() {
        File f = file();
        if (!f.exists()) return;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            int read = 0;
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n < 0) break;
                read += n;
            }
            JSONObject json;
            try {
                json = new JSONObject(new String(data, 0, read, StandardCharsets.UTF_8));
            } catch (org.json.JSONException e) {
                return;
            }
            if (json.optInt("version", 0) != VERSION) return;   // the old file is thrown away
            fetchedAt = json.optLong("at");
            day = json.optLong("day");
            JSONArray list = json.optJSONArray("games");
            if (list == null) return;
            synchronized (games) {
                games.clear();
                for (int i = 0; i < list.length(); i++) {
                    JSONObject o = list.optJSONObject(i);
                    if (o == null) continue;
                    Game g = new Game();
                    g.id = o.optInt("id");
                    g.league = o.optString("league");
                    g.home = o.optString("home");
                    g.away = o.optString("away");
                    g.channel = o.optString("channel");
                    g.venue = o.optString("venue");
                    g.country = o.optString("country");
                    g.channelCountry = o.optString("channelCountry");
                    g.homeLogo = o.optString("homeLogo");
                    g.awayLogo = o.optString("awayLogo");
                    g.leagueLogo = o.optString("leagueLogo");
                    g.at = o.optLong("at");
                    g.live = o.optBoolean("live");
                    games.add(g);
                }
            }
        } catch (Exception ignored) {
        }
    }
}
