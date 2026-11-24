package com.jcstanghelini.tvlauncher;

import android.app.Dialog;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fork additions for the Stanghelini home: family calendar (Google Calendar secret iCal link),
 * "Mis equipos" (Boca + Estudiantes de Caseros from ESPN) and the Google Drive photo source used by
 * the screensaver ({@link FotosView}, {@link FotosDream}, {@link FotosActivity}).
 */
public final class Familia {

    private Familia() {}

    static final Locale AR = new Locale("es", "AR");
    private static final String PREF = "familia_prefs";
    private static final String K_ICAL = "ical_url";
    private static final String K_DRIVE = "drive_folder";
    private static final String BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    /** Callback for the Settings text prompts (java.util.function needs API 24, the app supports 23). */
    public interface TextCb {
        void on(String value);
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static String icalUrl(Context c) {
        return prefs(c).getString(K_ICAL, "");
    }

    public static void setIcalUrl(Context c, String url) {
        String u = url == null ? "" : url.trim();
        // Google shows the link as webcal:// in some places.
        if (u.startsWith("webcal://")) u = "https://" + u.substring("webcal://".length());
        prefs(c).edit().putString(K_ICAL, u).apply();
        sEvents = null;
        sEventsTs = 0L;
        new File(c.getFilesDir(), "familia.ics").delete();
    }

    public static String driveFolder(Context c) {
        return prefs(c).getString(K_DRIVE, "");
    }

    public static void setDriveFolder(Context c, String v) {
        prefs(c).edit().putString(K_DRIVE, v == null ? "" : v.trim()).apply();
    }

    // =====================================================================================
    // HTTP
    // =====================================================================================

    static String httpGet(String url) throws Exception {
        return new String(httpBytes(url), StandardCharsets.UTF_8);
    }

    static byte[] httpBytes(String url) throws Exception {
        String u = url;
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(u).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", BROWSER_UA);
            try {
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = conn.getHeaderField("Location");
                    if (loc == null) throw new Exception("HTTP " + code);
                    u = new URL(new URL(u), loc).toString();
                    continue;
                }
                if (code != 200) throw new Exception("HTTP " + code);
                try (InputStream in = conn.getInputStream()) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int r;
                    while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
                    return bos.toByteArray();
                }
            } finally {
                conn.disconnect();
            }
        }
        throw new Exception("Too many redirects");
    }

    // =====================================================================================
    // FAMILY CALENDAR (iCal)
    // =====================================================================================

    public static final class Ev {
        public String title = "";
        public long start;
        public boolean allDay;
    }

    private static volatile List<Ev> sEvents;
    private static volatile long sEventsTs;
    private static volatile boolean sEventsBusy;
    private static volatile String sEventsError = "";

    /** Fetches (at most every 30 minutes unless forced) and expands the next 45 days of events. */
    public static void fetchEvents(final Context ctx, boolean force, final Runnable done) {
        final Context c = ctx.getApplicationContext();
        long now = System.currentTimeMillis();
        if (sEventsBusy || (!force && sEvents != null && now - sEventsTs < 30 * 60_000L)) {
            if (done != null) HomeDashboard.MAIN.post(done);
            return;
        }
        final String url = icalUrl(c);
        if (url.isEmpty()) {
            sEvents = new ArrayList<Ev>();
            if (done != null) HomeDashboard.MAIN.post(done);
            return;
        }
        sEventsBusy = true;
        HomeDashboard.EXEC.execute(() -> {
            File cache = new File(c.getFilesDir(), "familia.ics");
            String ics = null;
            try {
                ics = httpGet(url);
                if (!ics.contains("BEGIN:VCALENDAR")) throw new Exception("El link no es un calendario iCal");
                try (FileOutputStream fos = new FileOutputStream(cache)) {
                    fos.write(ics.getBytes(StandardCharsets.UTF_8));
                }
                sEventsError = "";
            } catch (Throwable t) {
                sEventsError = t.getMessage() != null ? t.getMessage() : "Sin conexión";
                ics = readFile(cache);
            }
            try {
                if (ics != null) {
                    long from = startOfToday();
                    sEvents = expand(ics, from, from + 45L * 86_400_000L);
                    sEventsTs = System.currentTimeMillis();
                }
            } catch (Throwable t) {
                sEventsError = "No se pudo leer el calendario";
            }
            sEventsBusy = false;
            if (done != null) HomeDashboard.MAIN.post(done);
        });
    }

    private static String readFile(File f) {
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    static long startOfToday() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    /** Events from now on: timed events stay listed for 2 hours after they start, all-day ones all day. */
    public static List<Ev> upcoming(int max) {
        List<Ev> all = sEvents;
        List<Ev> out = new ArrayList<Ev>();
        if (all == null) return out;
        long now = System.currentTimeMillis();
        long today = startOfToday();
        for (Ev e : all) {
            if (e.allDay ? e.start >= today : e.start >= now - 2 * 3_600_000L) {
                out.add(e);
                if (out.size() >= max) break;
            }
        }
        return out;
    }

    private static final class VEvent {
        Map<String, String> props = new HashMap<String, String>();
        Map<String, String> params = new HashMap<String, String>();
        List<String[]> exdates = new ArrayList<String[]>(); // {params, value}
    }

    /** Minimal RFC 5545 reader: VEVENTs with DTSTART, SUMMARY, RRULE, EXDATE and RECURRENCE-ID overrides. */
    static List<Ev> expand(String ics, long from, long to) {
        String unfolded = ics.replace("\r\n", "\n").replace("\n ", "").replace("\n\t", "");
        List<VEvent> evs = new ArrayList<VEvent>();
        VEvent cur = null;
        for (String line : unfolded.split("\n")) {
            if (line.equals("BEGIN:VEVENT")) {
                cur = new VEvent();
                continue;
            }
            if (line.equals("END:VEVENT")) {
                if (cur != null) evs.add(cur);
                cur = null;
                continue;
            }
            if (cur == null) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String head = line.substring(0, colon);
            String value = line.substring(colon + 1);
            int semi = head.indexOf(';');
            String name = (semi >= 0 ? head.substring(0, semi) : head).toUpperCase(Locale.ROOT);
            String params = semi >= 0 ? head.substring(semi + 1) : "";
            if (name.equals("EXDATE")) {
                for (String v : value.split(",")) cur.exdates.add(new String[]{params, v.trim()});
            } else if (!cur.props.containsKey(name)) {
                cur.props.put(name, value);
                cur.params.put(name, params);
            }
        }

        // RECURRENCE-ID instances replace one occurrence of their master series.
        Map<String, Set<Long>> overridden = new HashMap<String, Set<Long>>();
        for (VEvent v : evs) {
            String rid = v.props.get("RECURRENCE-ID");
            if (rid == null) continue;
            long t = parseDate(v.params.get("RECURRENCE-ID"), rid, null);
            String uid = v.props.get("UID");
            if (uid == null || t == Long.MIN_VALUE) continue;
            Set<Long> set = overridden.get(uid);
            if (set == null) overridden.put(uid, set = new HashSet<Long>());
            set.add(t);
        }

        List<Ev> out = new ArrayList<Ev>();
        for (VEvent v : evs) {
            String dt = v.props.get("DTSTART");
            if (dt == null) continue;
            if ("CANCELLED".equalsIgnoreCase(v.props.get("STATUS"))) continue;
            String dtParams = v.params.get("DTSTART");
            boolean allDay = dt.trim().length() == 8 || (dtParams != null && dtParams.toUpperCase(Locale.ROOT).contains("VALUE=DATE")
                    && !dtParams.toUpperCase(Locale.ROOT).contains("VALUE=DATE-TIME"));
            TimeZone tz = tzOf(dtParams, dt);
            long start = parseDate(dtParams, dt, null);
            if (start == Long.MIN_VALUE) continue;
            String title = unescape(v.props.containsKey("SUMMARY") ? v.props.get("SUMMARY") : "(sin título)");

            Set<Long> skip = new HashSet<Long>();
            for (String[] ex : v.exdates) {
                long t = parseDate(ex[0], ex[1], tz);
                if (t != Long.MIN_VALUE) skip.add(t);
            }
            Set<Long> ov = v.props.containsKey("RECURRENCE-ID") ? null : overridden.get(v.props.get("UID"));
            if (ov != null) skip.addAll(ov);

            String rrule = v.props.containsKey("RECURRENCE-ID") ? null : v.props.get("RRULE");
            List<Long> starts = rrule == null ? Collections.singletonList(start) : recur(rrule, start, tz, from, to);
            for (Long s : starts) {
                if (s < from || s >= to || skip.contains(s)) continue;
                Ev e = new Ev();
                e.title = title;
                e.start = s;
                e.allDay = allDay;
                out.add(e);
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(a.start, b.start));
        return out;
    }

    private static TimeZone tzOf(String params, String value) {
        if (value != null && value.trim().endsWith("Z")) return TimeZone.getTimeZone("UTC");
        if (params != null) {
            for (String p : params.split(";")) {
                if (p.toUpperCase(Locale.ROOT).startsWith("TZID=")) {
                    String id = p.substring(5).replace("\"", "");
                    TimeZone tz = TimeZone.getTimeZone(id);
                    // getTimeZone falls back to GMT for unknown ids; keep the device zone in that case.
                    if (!"GMT".equals(tz.getID()) || "GMT".equals(id)) return tz;
                }
            }
        }
        return TimeZone.getDefault();
    }

    /** Parses DATE or DATE-TIME. All-day dates are midnight in the device zone. */
    static long parseDate(String params, String raw, TimeZone fallbackTz) {
        if (raw == null) return Long.MIN_VALUE;
        String v = raw.trim();
        try {
            if (v.length() == 8) {
                SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd", Locale.US);
                f.setTimeZone(TimeZone.getDefault());
                return f.parse(v).getTime();
            }
            boolean utc = v.endsWith("Z");
            if (utc) v = v.substring(0, v.length() - 1);
            SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US);
            TimeZone tz = utc ? TimeZone.getTimeZone("UTC") : tzOf(params, raw);
            if (!utc && (params == null || !params.toUpperCase(Locale.ROOT).contains("TZID=")) && fallbackTz != null) tz = fallbackTz;
            f.setTimeZone(tz);
            return f.parse(v).getTime();
        } catch (Throwable t) {
            return Long.MIN_VALUE;
        }
    }

    private static final String[] DAYS = {"SU", "MO", "TU", "WE", "TH", "FR", "SA"};

    /** Expands DAILY/WEEKLY/MONTHLY/YEARLY rules with INTERVAL, COUNT, UNTIL and BYDAY (incl. "2SA"). */
    static List<Long> recur(String rrule, long start, TimeZone tz, long from, long to) {
        Map<String, String> r = new HashMap<String, String>();
        for (String part : rrule.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) r.put(part.substring(0, eq).toUpperCase(Locale.ROOT), part.substring(eq + 1));
        }
        String freq = r.containsKey("FREQ") ? r.get("FREQ") : "";
        int interval = 1;
        try {
            if (r.containsKey("INTERVAL")) interval = Math.max(1, Integer.parseInt(r.get("INTERVAL")));
        } catch (NumberFormatException ignored) {}
        int count = Integer.MAX_VALUE;
        try {
            if (r.containsKey("COUNT")) count = Integer.parseInt(r.get("COUNT"));
        } catch (NumberFormatException ignored) {}
        long until = Long.MAX_VALUE;
        if (r.containsKey("UNTIL")) {
            long u = parseDate(null, r.get("UNTIL"), tz);
            if (u != Long.MIN_VALUE) until = r.get("UNTIL").trim().length() == 8 ? u + 86_399_999L : u;
        }
        long end = Math.min(to, until);

        Calendar base = Calendar.getInstance(tz);
        base.setTimeInMillis(start);
        int h = base.get(Calendar.HOUR_OF_DAY), mi = base.get(Calendar.MINUTE), se = base.get(Calendar.SECOND);

        // BYDAY entries: {ordinal (0 = every), Calendar.DAY_OF_WEEK}
        List<int[]> byDay = new ArrayList<int[]>();
        if (r.containsKey("BYDAY")) {
            for (String d : r.get("BYDAY").split(",")) {
                d = d.trim().toUpperCase(Locale.ROOT);
                if (d.length() < 2) continue;
                String code = d.substring(d.length() - 2);
                int ord = 0;
                if (d.length() > 2) {
                    try {
                        ord = Integer.parseInt(d.substring(0, d.length() - 2).replace("+", ""));
                    } catch (NumberFormatException ignored) {}
                }
                for (int i = 0; i < 7; i++) if (DAYS[i].equals(code)) byDay.add(new int[]{ord, i + 1});
            }
        }

        List<Long> out = new ArrayList<Long>();
        int produced = 0;
        Calendar period = (Calendar) base.clone();
        for (int guard = 0; guard < 5000; guard++) {
            List<Long> cands = new ArrayList<Long>();
            if ("WEEKLY".equals(freq) && !byDay.isEmpty()) {
                Calendar wk = (Calendar) period.clone();
                wk.setFirstDayOfWeek(Calendar.MONDAY);
                wk.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);
                for (int i = 0; i < 7; i++) {
                    Calendar d = (Calendar) wk.clone();
                    d.add(Calendar.DAY_OF_MONTH, i);
                    for (int[] bd : byDay) if (bd[1] == d.get(Calendar.DAY_OF_WEEK)) cands.add(d.getTimeInMillis());
                }
            } else if ("MONTHLY".equals(freq) && !byDay.isEmpty()) {
                Calendar m = (Calendar) period.clone();
                m.set(Calendar.DAY_OF_MONTH, 1);
                int days = m.getActualMaximum(Calendar.DAY_OF_MONTH);
                for (int[] bd : byDay) {
                    List<Long> hits = new ArrayList<Long>();
                    for (int dd = 1; dd <= days; dd++) {
                        Calendar d = (Calendar) m.clone();
                        d.set(Calendar.DAY_OF_MONTH, dd);
                        if (d.get(Calendar.DAY_OF_WEEK) == bd[1]) hits.add(d.getTimeInMillis());
                    }
                    if (bd[0] == 0) cands.addAll(hits);
                    else if (bd[0] > 0 && bd[0] <= hits.size()) cands.add(hits.get(bd[0] - 1));
                    else if (bd[0] < 0 && -bd[0] <= hits.size()) cands.add(hits.get(hits.size() + bd[0]));
                }
            } else {
                cands.add(period.getTimeInMillis());
            }
            Collections.sort(cands);
            for (long cnd : cands) {
                Calendar cc = Calendar.getInstance(tz);
                cc.setTimeInMillis(cnd);
                cc.set(Calendar.HOUR_OF_DAY, h);
                cc.set(Calendar.MINUTE, mi);
                cc.set(Calendar.SECOND, se);
                cc.set(Calendar.MILLISECOND, 0);
                long t = cc.getTimeInMillis();
                if (t < start) continue;
                if (t > end || produced >= count) return out;
                produced++;
                if (t >= from) out.add(t);
            }
            switch (freq) {
                case "DAILY": period.add(Calendar.DAY_OF_MONTH, interval); break;
                case "WEEKLY": period.add(Calendar.WEEK_OF_YEAR, interval); break;
                case "MONTHLY": period.add(Calendar.MONTH, interval); break;
                case "YEARLY": period.add(Calendar.YEAR, interval); break;
                default: return out;
            }
            if (period.getTimeInMillis() > end + 7L * 86_400_000L) return out;
        }
        return out;
    }

    private static String unescape(String s) {
        return s.replace("\\n", " ").replace("\\N", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\").trim();
    }

    /** Small icon by keyword so football matches and birthdays stand out on the TV. */
    static String iconFor(String title) {
        String t = title.toLowerCase(AR);
        if (t.contains("partido") || t.contains("fútbol") || t.contains("futbol") || t.contains("fefi")) return "⚽ ";
        if (t.contains("cumple")) return "🎂 ";
        if (t.contains("viaje") || t.contains("vacaciones") || t.contains("vuelo")) return "✈ ";
        if (t.contains("médico") || t.contains("medico") || t.contains("turno") || t.contains("pediatra")) return "🩺 ";
        if (t.contains("colegio") || t.contains("escuela") || t.contains("acto")) return "🎒 ";
        return "• ";
    }

    static String whenLabel(Ev e) {
        long today = startOfToday();
        long dayMs = 86_400_000L;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(e.start);
        String day;
        long diff = (e.start - today) / dayMs;
        if (e.start >= today && diff == 0) day = "Hoy";
        else if (diff == 1) day = "Mañana";
        else if (diff > 1 && diff < 7) day = cap(new SimpleDateFormat("EEEE", AR).format(c.getTime()));
        else day = new SimpleDateFormat("EEE d/M", AR).format(c.getTime());
        return e.allDay ? day : day + " " + new SimpleDateFormat("HH:mm", AR).format(c.getTime());
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // =====================================================================================
    // MIS EQUIPOS (ESPN)
    // =====================================================================================

    /** {display name, ESPN league slug, ESPN team id, name fragment used to spot the team in a fixture} */
    static final String[][] TEAMS = {
        {"Boca", "arg.1", "5", "boca"},
        {"Estudiantes", "arg.2", "17352", "estudiantes"},
    };

    static final class TeamState {
        HomeDashboard.MatchItem live, next, last;
        List<HomeDashboard.MatchItem> upcoming = new ArrayList<HomeDashboard.MatchItem>();
        List<HomeDashboard.MatchItem> results = new ArrayList<HomeDashboard.MatchItem>();
    }

    private static final Map<String, TeamState> sTeams = new HashMap<String, TeamState>();
    private static volatile long sTeamsTs;
    private static volatile boolean sTeamsBusy;

    public static void fetchTeams(final Context ctx, boolean force, final Runnable done) {
        final Context c = ctx.getApplicationContext();
        boolean anyLive = false;
        synchronized (sTeams) {
            for (TeamState s : sTeams.values()) if (s.live != null) anyLive = true;
        }
        long ttl = anyLive ? 90_000L : 20 * 60_000L;
        if (sTeamsBusy || (!force && !sTeams.isEmpty() && System.currentTimeMillis() - sTeamsTs < ttl)) {
            if (done != null) HomeDashboard.MAIN.post(done);
            return;
        }
        sTeamsBusy = true;
        HomeDashboard.EXEC.execute(() -> {
            for (String[] t : TEAMS) {
                List<HomeDashboard.MatchItem> all = new ArrayList<HomeDashboard.MatchItem>();
                Set<String> seen = new HashSet<String>();
                String base = "https://site.api.espn.com/apis/site/v2/sports/soccer/" + t[1] + "/teams/" + t[2] + "/schedule";
                for (String url : new String[]{base, base + "?fixture=true"}) {
                    try {
                        for (HomeDashboard.MatchItem m : HomeDashboard.parseEspnEvents(c, HomeDashboard.httpGet(url), t[1])) {
                            String key = m.id.isEmpty() ? m.homeTeam + m.startEpochMs : m.id;
                            if (seen.add(key)) all.add(m);
                        }
                    } catch (Throwable ignored) {}
                }
                if (all.isEmpty()) continue; // keep the previous state when offline
                Collections.sort(all, (a, b) -> Long.compare(a.startEpochMs, b.startEpochMs));
                TeamState st = new TeamState();
                long now = System.currentTimeMillis();
                for (HomeDashboard.MatchItem m : all) {
                    if (m.isLive) st.live = m;
                    else if (m.isFinished) st.results.add(0, m);
                    else if (m.startEpochMs > now - 3 * 3_600_000L) st.upcoming.add(m);
                }
                st.next = st.upcoming.isEmpty() ? null : st.upcoming.get(0);
                st.last = st.results.isEmpty() ? null : st.results.get(0);
                synchronized (sTeams) {
                    sTeams.put(t[0], st);
                }
            }
            sTeamsTs = System.currentTimeMillis();
            sTeamsBusy = false;
            if (done != null) HomeDashboard.MAIN.post(done);
        });
    }

    static String fixtureLine(HomeDashboard.MatchItem m) {
        String teams = m.homeTeam.trim() + " vs " + m.awayTeam.trim();
        if (m.isLive) return "🔴 " + m.homeTeam.trim() + " " + m.homeScore + "-" + m.awayScore + " " + m.awayTeam.trim() + " · " + m.statusText;
        if (m.isFinished) return m.homeTeam.trim() + " " + m.homeScore + "-" + m.awayScore + " " + m.awayTeam.trim();
        return teams + " · " + whenLabel(matchEv(m));
    }

    private static Ev matchEv(HomeDashboard.MatchItem m) {
        Ev e = new Ev();
        e.start = m.startEpochMs;
        return e;
    }

    /** The line the card shows for a team: live > next match within 10 days > last result. */
    static String teamSummary(String name) {
        TeamState st;
        synchronized (sTeams) {
            st = sTeams.get(name);
        }
        if (st == null) return name + ": cargando…";
        if (st.live != null) return fixtureLine(st.live);
        if (st.next != null && st.next.startEpochMs - System.currentTimeMillis() < 10L * 86_400_000L) {
            return "Próximo: " + fixtureLine(st.next);
        }
        if (st.last != null) return "Último: " + fixtureLine(st.last);
        if (st.next != null) return "Próximo: " + fixtureLine(st.next);
        return name + ": sin partidos cargados";
    }

    // =====================================================================================
    // WIDGET CARDS
    // =====================================================================================

    private static TextView line(MainActivity host, float sp, int color, boolean bold) {
        TextView tv = new TextView(host);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        return tv;
    }

    private static LinearLayout cardColumn(MainActivity host, FrameLayout card, String header) {
        LinearLayout col = new LinearLayout(host);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView headerTitle = new TextView(host);
        HomeDashboard.styleUnifiedWidgetHeader(host, null, headerTitle, header);
        col.addView(headerTitle);
        card.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return col;
    }

    private static LinearLayout.LayoutParams topMargin(MainActivity host, int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = HomeDashboard.dp(host, dp);
        return lp;
    }

    static View createCalendarCard(final MainActivity host) {
        final FrameLayout card = HomeDashboard.createBaseGlassCard(host, 0xFF60A5FA);
        LinearLayout col = cardColumn(host, card, "Agenda familiar");
        final TextView[] rows = new TextView[3];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = line(host, i == 0 ? 11f : 9.5f, i == 0 ? Color.WHITE : 0xCCFFFFFF, i == 0);
            col.addView(rows[i], topMargin(host, i == 0 ? 5 : 3));
        }
        final Runnable bind = () -> {
            if (icalUrl(host).isEmpty()) {
                rows[0].setText("Sin calendario");
                rows[1].setText("Ajustes › Widgets de inicio › Casa");
                rows[2].setText("");
                return;
            }
            List<Ev> next = upcoming(3);
            if (next.isEmpty()) {
                rows[0].setText(sEvents == null ? "Cargando…" : "Nada en agenda");
                rows[1].setText(sEventsError.isEmpty() ? "" : sEventsError);
                rows[2].setText("");
                return;
            }
            for (int i = 0; i < rows.length; i++) {
                if (i < next.size()) {
                    Ev e = next.get(i);
                    rows[i].setText(iconFor(e.title) + whenLabel(e) + " · " + e.title);
                } else {
                    rows[i].setText("");
                }
            }
        };
        bind.run();
        fetchEvents(host, false, bind);
        HomeDashboard.sCardRefreshers.put(card, () -> fetchEvents(host, false, bind));
        card.setOnClickListener(v -> showCalendarDialog(host));
        return card;
    }

    static View createMyTeamsCard(final MainActivity host) {
        final FrameLayout card = HomeDashboard.createBaseGlassCard(host, 0xFFFACC15);
        LinearLayout col = cardColumn(host, card, "Mis equipos");
        final TextView[] rows = new TextView[TEAMS.length];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = line(host, 10f, Color.WHITE, i == 0);
            rows[i].setSingleLine(false);
            rows[i].setMaxLines(2);
            col.addView(rows[i], topMargin(host, i == 0 ? 5 : 4));
        }
        final Runnable bind = () -> {
            for (int i = 0; i < TEAMS.length; i++) rows[i].setText(teamSummary(TEAMS[i][0]));
        };
        bind.run();
        fetchTeams(host, false, bind);
        HomeDashboard.sCardRefreshers.put(card, () -> fetchTeams(host, false, bind));
        card.setOnClickListener(v -> showTeamsDialog(host));
        return card;
    }

    // =====================================================================================
    // DIALOGS
    // =====================================================================================

    private static void showListDialog(MainActivity host, String title, List<String> lines, String footer) {
        Dialog d = HomeDashboard.createNativeGlassDialog(host);
        LinearLayout panel = HomeDashboard.createModalContainer(host, HomeDashboard.dp(host, 620));
        TextView t = new TextView(host);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        panel.addView(t);

        ScrollView sv = new ScrollView(host);
        LinearLayout col = new LinearLayout(host);
        col.setOrientation(LinearLayout.VERTICAL);
        for (String s : lines) {
            TextView row = HomeDashboard.createActionPill(host, s);
            row.setSingleLine(false);
            col.addView(row, topMargin(host, 6));
        }
        sv.addView(col);
        panel.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, HomeDashboard.dp(host, 300)));
        if (footer != null && !footer.isEmpty()) {
            TextView f = line(host, 10f, 0x99FFFFFF, false);
            f.setSingleLine(false);
            f.setText(footer);
            panel.addView(f, topMargin(host, 10));
        }
        HomeDashboard.presentNativeGlassDialog(host, d, panel);
    }

    private static void showCalendarDialog(MainActivity host) {
        List<String> lines = new ArrayList<String>();
        String lastDay = "";
        for (Ev e : upcoming(40)) {
            lines.add(iconFor(e.title) + whenLabel(e) + "  ·  " + e.title);
        }
        if (lines.isEmpty()) lines.add(icalUrl(host).isEmpty()
                ? "Todavía no cargaste el link del calendario."
                : "No hay eventos en los próximos 45 días.");
        String footer = icalUrl(host).isEmpty()
                ? "Pegá el link secreto iCal en Ajustes › Widgets de inicio › Casa. Se saca en Google Calendar desde la compu: Configuración del calendario › Dirección secreta en formato iCal."
                : (sEventsError.isEmpty() ? "Se actualiza cada 30 minutos." : "Último error: " + sEventsError);
        showListDialog(host, "📅  Agenda familiar", lines, footer);
        fetchEvents(host, true, null);
    }

    private static void showTeamsDialog(MainActivity host) {
        List<String> lines = new ArrayList<String>();
        for (String[] t : TEAMS) {
            TeamState st;
            synchronized (sTeams) {
                st = sTeams.get(t[0]);
            }
            lines.add("— " + t[0].toUpperCase(AR) + " —");
            if (st == null) {
                lines.add("Sin datos todavía");
                continue;
            }
            if (st.live != null) lines.add(fixtureLine(st.live));
            for (int i = 0; i < Math.min(4, st.upcoming.size()); i++) lines.add(fixtureLine(st.upcoming.get(i)));
            for (int i = 0; i < Math.min(3, st.results.size()); i++) lines.add("✓ " + fixtureLine(st.results.get(i)));
        }
        showListDialog(host, "⚽  Mis equipos", lines,
                "Datos de ESPN. Los partidos de Beni se cargan en el calendario familiar con la palabra \"partido\".");
        fetchTeams(host, true, null);
    }

    // =====================================================================================
    // GOOGLE DRIVE PHOTOS + SCREENSAVER
    // =====================================================================================

    /** Accepts a folder link (…/folders/ID, ?id=ID) or a bare ID. */
    static String driveFolderId(String v) {
        if (v == null) return "";
        Matcher m = Pattern.compile("folders/([A-Za-z0-9_-]{10,})").matcher(v);
        if (m.find()) return m.group(1);
        m = Pattern.compile("[?&]id=([A-Za-z0-9_-]{10,})").matcher(v);
        if (m.find()) return m.group(1);
        return v.matches("[A-Za-z0-9_-]{10,}") ? v : "";
    }

    /**
     * Lists the files of a folder shared as "anyone with the link" using Drive's public embedded
     * folder view, so no API key or Google sign-in is needed on the TV. Call off the main thread.
     */
    static List<String> listDrivePhotoIds(Context c) throws Exception {
        String id = driveFolderId(driveFolder(c));
        if (id.isEmpty()) throw new Exception("Falta configurar la carpeta de Drive");
        String html = httpGet("https://drive.google.com/embeddedfolderview?id=" + id);
        List<String> out = new ArrayList<String>();
        Matcher m = Pattern.compile("id=\"entry-([A-Za-z0-9_-]{10,})\"").matcher(html);
        while (m.find()) {
            if (!out.contains(m.group(1)) && !m.group(1).equals(id)) out.add(m.group(1));
        }
        if (out.isEmpty()) throw new Exception("La carpeta está vacía o no está compartida con el link");
        return out;
    }

    static String drivePhotoUrl(String fileId, int widthPx) {
        return "https://drive.google.com/thumbnail?id=" + fileId + "&sz=w" + Math.max(640, widthPx);
    }

    static String dreamComponent(Context c) {
        return new ComponentName(c, FotosDream.class).flattenToString();
    }

    public static boolean isScreensaverActive(Context c) {
        try {
            String cur = Settings.Secure.getString(c.getContentResolver(), "screensaver_components");
            return cur != null && cur.contains(dreamComponent(c));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Sets the photo slideshow as the system screensaver. Needs WRITE_SECURE_SETTINGS (granted by Easy Setup). */
    public static String activateScreensaver(Context c) {
        String comp = dreamComponent(c);
        try {
            ContentResolver cr = c.getContentResolver();
            Settings.Secure.putString(cr, "screensaver_components", comp);
            Settings.Secure.putInt(cr, "screensaver_enabled", 1);
            return "Listo: el protector de pantalla ahora muestra las fotos de la familia";
        } catch (Throwable t) {
            return "Falta un permiso. Corré la Configuración fácil del launcher, o desde la compu: "
                    + "adb shell settings put secure screensaver_components " + comp;
        }
    }
}
