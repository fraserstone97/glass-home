package app.glasshome.launcher;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import app.glasshome.launcher.SearchActivity.Result;

/**
 * What Search understands. A query is first read like a sentence: spoken filler goes ("search for",
 * "show me", "play"), and the useful parts are taken out — a service ("on Netflix"), a year
 * ("Dune 2021"), a season ("series 2"), a kind ("films", "shows"), genres ("comedies", "scary
 * films"), freshness ("new", "best") and people ("films with Tom Hardy"). What's left is the title.
 *
 * Then it answers in shelves, the way the Apple TV app does: Top Results for the title (matched
 * word by word, forgiving UK/US spelling and typos), More Like This for a clear match, the films
 * and series of an actor or director, or a genre to browse when that's what was asked for.
 * Everything is kept to what UK viewers watch and can watch here.
 */
final class SearchBrain {

    static final int MAX = 18;

    /** One shelf of answers. */
    static final class Shelf {
        final String heading;
        final List<Result> items;

        Shelf(String heading, List<Result> items) {
            this.heading = heading;
            this.items = items;
        }
    }

    // ---------------------------------------------------------------- reading the query

    static final class Query {
        String text = "";
        /** The whole request with the spoken filler gone, for when a reading doesn't pan out. */
        String full = "";
        /** The title with the year said in it, for titles named after one (Blade Runner 2049). */
        String titleYear;
        Suggestions.Provider service;
        int year, yearMin, yearMax;
        /** "80s" for a decade, for the shelf's name. */
        String decade;
        /** "movie", "tv" or null for either. */
        String type;
        /** The genres asked for, in the order they were said ("horror comedies"). */
        final List<Genre> genres = new ArrayList<>();
        boolean fresh, best, person;
        /** "directed by" (true), "starring" (false), or just a name (null: whatever they're known for). */
        Boolean directed;
        /** "films like Inception": text is the title to go by. */
        boolean like;
        /** "films about sharks": the subject, and how it was put ("About", "Set in"). */
        String about, aboutWord;
        /** Nothing left but genres, kind, decade and freshness: browse rather than search. */
        boolean browse() {
            return text.isEmpty() && about == null && !like;
        }

        /** The age ratings asked for (kids', family), or null. */
        String[] ages() {
            for (Genre g : genres) if (g.ages != null) return g.ages;
            return null;
        }

        boolean has(String code) {
            for (Genre g : genres) for (String c : g.codes) if (c.equals(code)) return true;
            return false;
        }
    }

    static final String[] LEAD = {"can you", "could you", "would you", "please", "hey", "ok", "okay", "i want to watch",
            "i wanna watch", "i'd like to watch", "let's watch", "lets watch", "i'm looking for", "im looking for",
            "search for", "search", "find me", "find", "look for", "look up", "show me", "get me", "put on",
            "recommend me", "recommend", "suggest", "what's", "whats", "what is", "what are", "is there", "are there",
            "have you got", "do you have", "watch", "play", "open", "start", "launch", "go to", "some", "something",
            "any", "anything"};

    /** Words that only describe what's wanted: with nothing else, the query is a browse. */
    static final Set<String> DESCRIBE = new HashSet<>(Arrays.asList(
            "the", "a", "an", "to", "watch", "some", "something", "anything", "any", "all", "on", "for", "me", "and",
            "of", "or", "should", "i", "we", "tonight", "today", "this", "weekend", "evening", "is", "there", "are",
            "what", "whats", "stuff", "things", "good", "his", "her", "their", "old", "classic", "classics"));

    /**
     * A genre as people say it, with JustWatch's codes for it. A title fits when it has all of them
     * (true crime is crime and documentary) or, for an "any" genre, one of them (kids' means family
     * or animation). Its names go in shelf headings: "True Crime Documentaries", "Kids' Films".
     */
    static final class Genre {
        final String[] words, codes;
        final boolean all;
        final String adj, plural;
        /** UK age ratings it's limited to: kids' means U and PG, not South Park. */
        String[] ages;

        Genre ages(String... ages) {
            this.ages = ages;
            return this;
        }

        Genre(String words, boolean all, String adj, String plural, String... codes) {
            this.words = words.split("\\|");
            this.all = all;
            this.adj = adj;
            this.plural = plural;
            this.codes = codes;
        }

        boolean fits(List<String> have) {
            int n = 0;
            for (String c : codes) if (have.contains(c)) n++;
            return all ? n == codes.length : n > 0;
        }
    }

    /** Longer phrases first, so "true crime" is read before "crime". */
    static final List<Genre> GENRES = Arrays.asList(
            new Genre("true crime", true, "True Crime", "True Crime", "crm", "doc"),
            new Genre("romantic comedies|romantic comedy|rom coms|rom com|romcoms|romcom", true, "Romantic Comedy", "Romcoms", "rma", "cmy"),
            new Genre("science fiction|sci fi|scifi", false, "Sci-Fi", "Sci-Fi", "scf"),
            new Genre("comedies|comedy|funny|sitcoms|sitcom|feel good", false, "Comedy", "Comedies", "cmy"),
            new Genre("horrors|horror|scary|spooky", false, "Horror", "Horror", "hrr"),
            new Genre("thrillers|thriller|mysteries|mystery|suspense", false, "Thriller", "Thrillers", "trl"),
            new Genre("crime", false, "Crime", "Crime", "crm"),
            new Genre("dramas|drama", false, "Drama", "Dramas", "drm"),
            new Genre("documentaries|documentary|docs", false, "Documentary", "Documentaries", "doc"),
            new Genre("romance|romantic", false, "Romantic", "Romance", "rma"),
            new Genre("fantasy", false, "Fantasy", "Fantasy", "fnt"),
            new Genre("action|adventure", false, "Action", "Action", "act"),
            new Genre("cartoons|cartoon", false, "Cartoon", "Cartoons", "ani").ages("U", "PG", "12"),
            new Genre("animated|animation", false, "Animated", "Animation", "ani"),
            new Genre("kids|kid|childrens|children", false, "Kids'", "Kids' Films and Series", "fml").ages("U", "PG"),
            new Genre("family", false, "Family", "Family Films and Series", "fml").ages("U", "PG", "12A", "12"),
            new Genre("reality", false, "Reality", "Reality TV", "rly"),
            new Genre("war", false, "War", "War", "war"),
            new Genre("westerns|western", false, "Western", "Westerns", "wsn"),
            new Genre("history|historical|period", false, "Period", "Period Dramas", "hst"),
            new Genre("sports|sport", false, "Sport", "Sport", "spt"),
            new Genre("musicals|musical|music", false, "Music", "Music", "msc"));

    static Genre genre(String word) {
        for (Genre g : GENRES) for (String w : g.words) if (w.equals(word)) return g;
        return null;
    }

    /** JustWatch's codes, rarest first: a browse asks for the rarest and checks the rest itself. */
    static final List<String> RARE = Arrays.asList("doc", "rma", "hrr", "wsn", "war", "msc", "spt", "ani", "fml",
            "rly", "scf", "fnt", "hst", "trl", "crm", "act", "cmy", "drm");

    /** The heading words for the genres asked for: "True Crime Documentaries", "Comedy Films". */
    static String genreName(Query q, String kind) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < q.genres.size(); i++) {
            Genre g = q.genres.get(i);
            String part = i == q.genres.size() - 1 && kind.isEmpty() ? g.plural : g.adj;
            if (b.indexOf(part) >= 0) continue;
            b.append(b.length() > 0 ? " " : "").append(part);
        }
        return (b + " " + kind).trim();
    }

    /** Spoken and typed names for each service. */
    static final String[][] SERVICES = {
            {"netflix", "8"}, {"prime video", "9"}, {"amazon prime", "9"}, {"prime", "9"}, {"amazon", "9"},
            {"disney plus", "337"}, {"disney+", "337"}, {"disney", "337"}, {"apple tv plus", "350"}, {"apple tv", "350"},
            {"bbc iplayer", "38"}, {"iplayer", "38"}, {"the bbc", "38"}, {"bbc", "38"}, {"itvx", "41"}, {"itv x", "41"},
            {"itv", "41"}, {"channel 4", "103"}, {"channel four", "103"}, {"now tv", "39"}, {"now", "39"},
            {"sky", "39"}, {"hbo max", "1899"}, {"hbo", "1899"}, {"max", "1899"}, {"paramount plus", "531"},
            {"paramount", "531"}};

    static final java.util.regex.Pattern LIKE = java.util.regex.Pattern.compile(
            "^(?:(?:(films?|movies?|shows?|series|tv shows?|programmes?|documentar(?:y|ies)|something|anything|stuff|others?|titles?)"
                    + " (?:just |a bit |kind of |a little )?(?:like|similar to))|similar to|more like) (.+)$");
    static final java.util.regex.Pattern PERSON = java.util.regex.Pattern.compile(
            "^(?:(.*?) )?(with|starring|featuring|directed by|by) (.+)$");
    static final java.util.regex.Pattern ABOUT = java.util.regex.Pattern.compile("^(.+?) (about|set in|based on) (.+)$");
    static final java.util.regex.Pattern YEAR = java.util.regex.Pattern.compile(" (from |in )?((19[2-9]|20[0-4])\\d) ");
    static final java.util.regex.Pattern DECADE = java.util.regex.Pattern.compile(
            " (?:(?:from|in) )?(?:the )?(?:(19[2-9]0s|20[0-2]0s|[2-9]0s|00s|noughties)|(twenties|thirties|forties|fifties|sixties|seventies|eighties|nineties)) ");
    static final Set<String> OCCASIONS = new HashSet<>(Arrays.asList("christmas", "xmas", "halloween", "easter",
            "valentines", "bonfire night", "new year", "summer", "winter"));
    static final String[] TENS = {"twenties", "thirties", "forties", "fifties", "sixties", "seventies", "eighties", "nineties"};

    static Query read(String raw) {
        Query q = new Query();
        String s = " " + raw.toLowerCase(Locale.UK).replace("&", " and ").replace("+", " plus ")
                .replaceAll("[^a-z0-9' ]", " ").replace("'", "").replaceAll("\\s+", " ").trim() + " ";
        // Spoken filler at the start, as many as there are ("can you please show me ..."), but not the
        // "something" of "something like Slow Horses".
        boolean again = true;
        while (again) {
            again = false;
            for (String lead : LEAD) {
                String l = " " + lead.replace("'", "") + " ";
                if (!s.startsWith(l) || s.length() <= l.length() + 1) continue;
                String after = s.substring(l.length() - 1);
                if (after.startsWith(" like ") || after.startsWith(" similar ") || after.startsWith(" about ")) continue;
                s = after;
                again = true;
            }
        }
        s = s.replace(" for me ", " ").replace(" please ", " ");
        q.full = s.trim();
        // "something like Slow Horses", "films similar to Inception": the title to go by.
        java.util.regex.Matcher like = LIKE.matcher(s.trim());
        if (like.find()) {
            q.like = true;
            String k = like.group(1);
            if (k != null && !k.matches("something|anything|stuff|others?|titles?")) {
                q.type = k.startsWith("documentar") ? null : kind(k);
                if (k.startsWith("documentar")) q.genres.add(genre("documentaries"));
            }
            s = " " + like.group(2) + " ";
        }
        // "on Netflix", "from the BBC"; or just "Netflix", for what's on it.
        for (String[] sv : SERVICES) {
            for (String pre : new String[]{" on ", " from ", " in "}) {
                String k = pre + sv[0] + " ";
                int at = s.indexOf(k);
                if (at >= 0) {
                    q.service = provider(Integer.parseInt(sv[1]));
                    s = s.substring(0, at) + " " + s.substring(at + k.length());
                    break;
                }
            }
            if (q.service == null && !q.like && s.trim().equals(sv[0])) {
                q.service = provider(Integer.parseInt(sv[1]));
                if (q.service != null) s = " ";
            }
            if (q.service != null) break;
        }
        // "the latest episode of", "season 2", "series 3 episode 4": the programme is the search.
        s = s.replaceAll("^ (?:the )?(?:(?:latest|newest|new|last|next|first) )?(?:episode|season|series)(?: \\d+)? of ", " ");
        s = s.replaceAll(" (season|series|episode|part) (\\d+|one|two|three|four|five|six|seven|eight|nine|ten) ", " ");
        // A year: "dune 2021", "dune from 2021", "the 2021 dune". Kept in titleYear for titles that
        // have it in their name (Blade Runner 2049).
        java.util.regex.Matcher y = YEAR.matcher(s);
        if (y.find() && s.trim().length() > 4) {
            q.year = Integer.parseInt(y.group(2));
            q.titleYear = s.trim();
            s = s.substring(0, y.start()) + " " + s.substring(y.end());
        }
        // A decade: "80s films", "comedies from the nineties" (but That '70s Show is a title).
        String withDecade = s;
        java.util.regex.Matcher d = DECADE.matcher(s);
        if (d.find()) {
            int start;
            if (d.group(2) != null) {
                start = 1920 + 10 * Arrays.asList(TENS).indexOf(d.group(2));
            } else {
                String g = d.group(1);
                start = g.equals("noughties") || g.equals("00s") ? 2000
                        : g.length() == 5 ? Integer.parseInt(g.substring(0, 4))
                        : g.equals("20s") ? 2020 : 1900 + Integer.parseInt(g.substring(0, 2));
            }
            q.yearMin = start;
            q.yearMax = start + 9;
            q.decade = start >= 2000 && start < 2010 ? "2000s" : start >= 2010 ? start + "s" : (start % 100) + "s";
            s = s.substring(0, d.start()) + " " + s.substring(d.end());
        }
        if (q.like) {
            q.text = s.trim().replaceAll(" (film|movie|show|series|tv series|tv show|programme)$", "");
            return q;
        }
        // People: "films with Tom Hardy", "comedies starring Will Ferrell", "directed by Greta Gerwig";
        // not "Gone with the Wind", where the words before aren't a description.
        java.util.regex.Matcher p = PERSON.matcher(s.trim());
        if (p.find() && (p.group(1) == null || describe(new Query(), " " + p.group(1) + " ").isEmpty())) {
            if (p.group(1) != null) describe(q, " " + p.group(1) + " ");
            q.person = true;
            q.directed = p.group(2).endsWith("by");
            q.text = p.group(3).trim();
            return q;
        }
        // A subject: "films about sharks", "documentaries about space", "series set in Scotland".
        java.util.regex.Matcher a = ABOUT.matcher(s.trim());
        if (a.find() && describe(new Query(), " " + a.group(1) + " ").isEmpty()) {
            describe(q, " " + a.group(1) + " ");
            q.about = a.group(3).trim();
            q.aboutWord = a.group(2).equals("about") ? "About" : a.group(2).equals("set in") ? "Set in" : "Based on";
            return q;
        }
        // Kind, freshness and genres, wherever they are: "new comedy films", "the best thrillers".
        String rest = describe(q, s);
        // An occasion with a kind: "Christmas films", "something for Halloween".
        if (OCCASIONS.contains(rest) && (q.type != null || !q.genres.isEmpty() || q.full.startsWith("something"))) {
            q.about = rest;
            q.aboutWord = "";
            return q;
        }
        if (rest.isEmpty()) {
            q.text = "";  // a browse: "new comedy films on netflix", "80s films", "netflix"
        } else {
            // A title search keeps its own words ("New Girl", "The Crime Show"); only the service,
            // year, season and filler are taken out, plus a leading "the film"/"the show".
            q.genres.clear();
            q.fresh = q.best = false;
            q.type = null;
            q.yearMin = q.yearMax = 0;
            q.decade = null;
            String t = withDecade.trim();
            java.util.regex.Matcher lead = java.util.regex.Pattern.compile(
                    "^(?:the )?(film|movie|show|series|tv show|programme|documentary) (.+)$").matcher(t);
            java.util.regex.Matcher tail = java.util.regex.Pattern.compile(
                    "^(.+?) (film|movie|show|series|tv series|tv show|programme)$").matcher(t);
            if (lead.find()) {
                q.type = kind(lead.group(1));
                t = lead.group(2);
            } else if (tail.find()) {
                // "slow horses series", "dune film": a trailing kind is a hint, not part of the name.
                q.type = kind(tail.group(2));
                t = tail.group(1);
            }
            q.text = t;
        }
        return q;
    }

    /**
     * Takes the describing words out of s (kind, freshness, genres) into q, and returns what's left
     * once filler is gone too: empty when s only described what's wanted.
     */
    static String describe(Query q, String s) {
        String[] kinds = {"tv shows", "tv show", "tv series", "films", "film", "movies", "movie", "shows", "show",
                "series", "programmes", "programme", "tv"};
        String bare = s;
        for (String k : kinds) {
            if (bare.contains(" " + k + " ")) {
                if (q.type == null) q.type = kind(k);
                bare = bare.replace(" " + k + " ", " ");
            }
        }
        for (String f : new String[]{"new", "newest", "latest", "recent", "just added", "this year"}) {
            if (bare.contains(" " + f + " ")) {
                q.fresh = true;
                bare = bare.replace(" " + f + " ", " ");
            }
        }
        if ((bare.contains(" old ") || bare.contains(" classic ") || bare.contains(" classics ")) && q.yearMax == 0) q.yearMax = 1999;
        for (String f : new String[]{"best", "top", "good", "great", "popular", "trending", "highly rated",
                "award winning", "classic", "classics"}) {
            if (bare.contains(" " + f + " ")) {
                q.best = true;
                bare = bare.replace(" " + f + " ", " ");
            }
        }
        Map<Genre, Integer> at = new HashMap<>();
        for (Genre g : GENRES) {
            for (String w : g.words) {
                if (!bare.contains(" " + w + " ")) continue;
                if (!q.genres.contains(g)) {
                    q.genres.add(g);
                    at.put(g, s.indexOf(" " + w + " "));
                }
                bare = bare.replace(" " + w + " ", " ");
            }
        }
        q.genres.sort((x, y) -> Integer.compare(at.containsKey(x) ? at.get(x) : -1, at.containsKey(y) ? at.get(y) : -1));
        StringBuilder rest = new StringBuilder();
        for (String w : bare.trim().split(" ")) if (!w.isEmpty() && !DESCRIBE.contains(w)) rest.append(w).append(' ');
        return rest.toString().trim();
    }

    static String kind(String word) {
        return word.startsWith("film") || word.startsWith("movie") ? "movie" : "tv";
    }

    static Suggestions.Provider provider(int id) {
        for (Suggestions.Provider p : Suggestions.PROVIDERS) if (p.id == id) return p;
        return null;
    }

    // ---------------------------------------------------------------- answering

    final Context c;
    final List<Suggestions.Provider> mine = new ArrayList<>();
    static final ExecutorService POOL = Executors.newFixedThreadPool(3);

    SearchBrain(Context c) {
        this.c = c.getApplicationContext();
        for (Suggestions.Provider p : Suggestions.PROVIDERS) if (Suggestions.installed(c, p.pkg)) mine.add(p);
    }

    /** Answers from the last few minutes, so going back a letter or repeating a search is instant. */
    static final android.util.LruCache<String, Object[]> ANSWERS = new android.util.LruCache<>(24);

    /** Hands over the first shelves as soon as they're known (early), then returns them all. */
    List<Shelf> answer(String raw, java.util.function.Consumer<List<Shelf>> early) throws Exception {
        String key = raw.trim().toLowerCase(Locale.UK);
        Object[] hit = ANSWERS.get(key);
        if (hit != null && System.currentTimeMillis() - (Long) hit[0] < 10 * 60_000L) {
            @SuppressWarnings("unchecked") List<Shelf> kept = (List<Shelf>) hit[1];
            return copy(kept);
        }
        List<Shelf> out = work(raw, early);
        if (!out.isEmpty()) ANSWERS.put(key, new Object[]{System.currentTimeMillis(), copy(out)});
        return out;
    }

    /** Fresh shelves of the same results (each showing keeps its own picture views). */
    static List<Shelf> copy(List<Shelf> in) {
        List<Shelf> out = new ArrayList<>();
        for (Shelf sh : in) out.add(new Shelf(sh.heading, new ArrayList<>(sh.items)));
        return out;
    }

    List<Shelf> work(String raw, java.util.function.Consumer<List<Shelf>> early) throws Exception {
        Query q = read(raw);
        List<Shelf> out = new ArrayList<>();
        if (q.like) {
            List<Shelf> l = like(q);
            if (l != null) return l;
            q = plain(q.full);  // no such title: an ordinary search
        }
        if (q.about != null) return about(q);
        if (q.browse()) {
            boolean anything = q.genres.isEmpty() && q.service == null && q.type == null && !q.fresh && !q.best
                    && q.yearMin == 0 && q.yearMax == 0;
            out.add(anything ? new Shelf("Popular Now", popularNow()) : new Shelf(browseHeading(q), browse(q)));
            return out;
        }
        // The title search and the people search run side by side.
        Query fq = q;
        Future<Person> people = q.text.length() >= 4 && Suggestions.hasToken(c)
                && (q.text.contains(" ") || q.text.length() >= 5 || q.person) ? POOL.submit(() -> people(fq)) : null;
        List<Result> top = titles(q);
        Result best = top.isEmpty() ? null : top.get(0);
        boolean clear = best != null && matchScore(q.text, best.title) >= 85;
        List<Result> cast = null;
        String who = null, name = "";
        if (people != null) {
            try {
                Person p = people.get(6, TimeUnit.SECONDS);
                if (p != null) {
                    cast = p.works;
                    who = p.heading;
                    name = p.name;
                }
            } catch (Exception ignored) {
                // no people this time
            }
        }
        if (q.person && (cast == null || cast.isEmpty())) {
            // "Gone with the Wind" read as a person: it's a title after all.
            Query t = plain(q.full);
            return titleShelves(t, titles(t), null, null, early);
        }
        boolean personFirst = cast != null && !cast.isEmpty()
                && (q.person || !clear || matchScore(q.text, name) >= 100 && matchScore(q.text, best.title) < 100);
        if (personFirst) {
            // An actor or director, not a title: their work first, and only titles that really
            // match the name after it.
            out.add(new Shelf(who, cast));
            top.removeIf(r -> matchScore(fq.text, r.title) < 85);
            if (!top.isEmpty()) out.add(new Shelf("Top Results", top));
            return out;
        }
        return titleShelves(q, top, cast, who, early);
    }

    /** Top Results, More Like This for a clear match, then an actor's work if the name was theirs too. */
    List<Shelf> titleShelves(Query q, List<Result> top, List<Result> cast, String who,
                             java.util.function.Consumer<List<Shelf>> early) {
        List<Shelf> out = new ArrayList<>();
        if (!top.isEmpty()) out.add(new Shelf("Top Results", top));
        Result best = top.isEmpty() ? null : top.get(0);
        if (best != null && matchScore(q.text, best.title) >= 85) {
            if (early != null) early.accept(copy(out));
            Set<String> have = new HashSet<>();
            for (Result r : top) have.add(norm(r.title));
            List<Result> more = new ArrayList<>();
            for (Result r : similar(best)) if (have.add(norm(r.title))) more.add(r);
            if (!more.isEmpty()) out.add(new Shelf("More Like This", more));
        }
        if (cast != null && !cast.isEmpty()) out.add(new Shelf(who, cast));
        return out;
    }

    /** A query that's just a title. */
    static Query plain(String text) {
        Query q = new Query();
        q.full = q.text = text;
        return q;
    }

    /**
     * "Something like Slow Horses", "films like Inception on Netflix": what people who watch it
     * watch too, of the kind asked for, then the title itself. Null when there's no such title.
     */
    List<Shelf> like(Query q) throws Exception {
        List<Result> top = titles(plain(q.text));
        if (top.isEmpty() || matchScore(q.text, top.get(0).title) < 65) return null;
        Result to = top.get(0);
        List<Result> sim = similar(to);
        sim.removeIf(r -> q.type != null && !q.type.equals(r.type) || !wanted(q, r));
        if (q.service != null) {
            // On that service first.
            List<Result> on = new ArrayList<>(), off = new ArrayList<>();
            for (Result r : sim) (r.on(q.service.id) ? on : off).add(r);
            on.addAll(off);
            sim = on;
        }
        if (sim.isEmpty()) return null;
        String kind = q.has("doc") ? "Documentaries" : "tv".equals(q.type) ? "Series"
                : "movie".equals(q.type) ? "Films" : "More";
        List<Shelf> out = new ArrayList<>();
        out.add(new Shelf(kind + " Like " + to.title, sim));
        top.removeIf(r -> matchScore(q.text, r.title) < Math.min(100, matchScore(q.text, to.title)));
        if (!top.isEmpty()) out.add(new Shelf("Top Results", top));
        return out;
    }

    /** The genres and years asked for, if any. */
    static boolean wanted(Query q, Result r) {
        for (Genre g : q.genres) if (!g.fits(r.genres)) return false;
        int y = Suggestions.parseInt(r.year == null ? "" : r.year);
        return (q.yearMin == 0 || y >= q.yearMin) && (q.yearMax == 0 || y <= q.yearMax);
    }

    /**
     * "Films about sharks", "documentaries about space", "series set in Scotland": TMDB's keywords
     * for the subject, then the best-known films and series with them that can be watched in the UK.
     * A title that happens to be worded that way ("Something About Mary") comes first.
     */
    List<Shelf> about(Query q) throws Exception {
        List<Shelf> out = new ArrayList<>();
        Query named = plain(q.full);
        Future<List<Result>> titled = POOL.submit(() -> titles(named));
        List<Result> found = Suggestions.hasToken(c) ? subject(q) : new ArrayList<>();
        List<Result> top = new ArrayList<>();
        try {
            top = titled.get(8, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // the subject on its own
        }
        top.removeIf(r -> matchScore(named.text, r.title) < 65);
        if (!top.isEmpty()) out.add(new Shelf("Top Results", top));
        if (!found.isEmpty()) {
            String kind = q.has("doc") ? "Documentaries" : "tv".equals(q.type) ? "Series"
                    : "movie".equals(q.type) ? "Films" : "Films and Series";
            String dec = q.decade != null ? q.decade + " " : "";
            String what = genreName(q, "tv".equals(q.type) ? "Series" : "movie".equals(q.type) ? "Films" : "");
            // "Christmas Films", "Films About Sharks".
            out.add(new Shelf(q.aboutWord.isEmpty()
                    ? dec + titleCase(q.about.replace("xmas", "christmas")) + " " + (what.isEmpty() ? "Films and Series" : what)
                    : dec + kind + " " + q.aboutWord + " " + titleCase(q.about), found));
        }
        return out;
    }

    static final Set<String> WEST_LANG = new HashSet<>(Arrays.asList(
            "en", "fr", "de", "es", "it", "nl", "sv", "no", "nb", "da", "fi", "is", "pt", "pl", "cs", "ga", "cy"));

    List<Result> subject(Query q) throws Exception {
        String subject = q.about.replace("xmas", "christmas");
        String one = singular(norm(subject));
        JSONArray ks = Suggestions.get(c, "/search/keyword?query=" + enc(subject)).optJSONArray("results");
        List<String> ids = new ArrayList<>();
        for (int i = 0; ks != null && i < ks.length() && i < 20; i++) {
            JSONObject k = ks.getJSONObject(i);
            String name = norm(k.optString("name"));
            if (singular(name).equals(one)) ids.add(0, k.optString("id"));
            else if (ids.size() < 3 && (" " + name + " ").contains(" " + one)
                    && name.split(" ").length <= one.split(" ").length + 1) ids.add(k.optString("id"));
        }
        if (ids.isEmpty()) return new ArrayList<>();
        int thisYear = Calendar.getInstance().get(Calendar.YEAR);
        List<Future<JSONArray>> parts = new ArrayList<>();
        List<String> kinds = new ArrayList<>();
        for (String type : q.type == null ? new String[]{"movie", "tv"} : new String[]{q.type}) {
            boolean tv = type.equals("tv");
            StringBuilder u = new StringBuilder("/discover/").append(type).append("?with_keywords=")
                    .append(enc(String.join("|", ids.subList(0, Math.min(4, ids.size())))))
                    .append("&sort_by=popularity.desc&vote_count.gte=").append(tv ? 40 : 120)
                    .append("&watch_region=GB&with_watch_monetization_types=").append(enc("flatrate|free|ads|rent|buy"))
                    .append("&language=en-GB&include_adult=false");
            String g = tmdbGenres(q.genres, tv);
            if (!g.isEmpty()) u.append("&with_genres=").append(enc(g));
            if (!q.has("fml") && !q.has("ani")) u.append("&without_genres=").append(enc(tv ? "16,10762" : "16,10751"));
            if (q.ages() != null && !tv) u.append("&certification_country=GB&certification=").append(enc(String.join("|", q.ages())));
            String date = tv ? "first_air_date" : "primary_release_date";
            int min = q.fresh ? thisYear - 2 : q.yearMin;
            if (min > 0) u.append('&').append(date).append(".gte=").append(min).append("-01-01");
            if (q.yearMax > 0) u.append('&').append(date).append(".lte=").append(q.yearMax).append("-12-31");
            String path = u.toString();
            parts.add(POOL.submit(() -> Suggestions.get(c, path).optJSONArray("results")));
            kinds.add(type);
        }
        List<JSONObject> works = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            JSONArray a;
            try {
                a = parts.get(i).get(8, TimeUnit.SECONDS);
            } catch (Exception e) {
                continue;
            }
            for (int k = 0; a != null && k < a.length(); k++) {
                JSONObject w = a.getJSONObject(k);
                if (!WEST_LANG.contains(w.optString("original_language")) || w.isNull("poster_path")) continue;
                w.put("media_type", kinds.get(i));
                works.add(w);
            }
        }
        // Well known and much watched first.
        works.sort((x, y) -> Double.compare(weight(y), weight(x)));
        List<Result> out = new ArrayList<>();
        for (JSONObject w : works) {
            Result r = tmdbResult(w);
            r.role = "";
            out.add(r);
            if (out.size() >= MAX) break;
        }
        return out;
    }

    static double weight(JSONObject w) {
        return Math.pow(w.optDouble("vote_count", 1), 0.7) * Math.pow(w.optDouble("popularity", 1), 0.3)
                * w.optDouble("vote_average", 6) / 7;
    }

    /** TMDB's genre ids for the genres asked for: "80,99" (crime and documentary), "10751|16" (family or animation). */
    static String tmdbGenres(List<Genre> gs, boolean tv) {
        List<String> and = new ArrayList<>();
        for (Genre g : gs) {
            List<String> ids = tmdbIds(g, tv);
            if (ids.isEmpty()) continue;
            and.add(g.all ? String.join(",", ids) : gs.size() == 1 ? String.join("|", ids) : ids.get(0));
        }
        return String.join(",", and);
    }

    static List<String> tmdbIds(Genre g, boolean tv) {
        List<String> ids = new ArrayList<>();
        for (String code : g.codes) {
            int[][] t = TMDB_GENRES.get(code);
            if (t != null && t[tv ? 1 : 0].length > 0) ids.add(String.valueOf(t[tv ? 1 : 0][0]));
        }
        return ids;
    }

    /** JustWatch's genre codes to TMDB's, for films and for series (TMDB has no horror series). */
    static final Map<String, int[][]> TMDB_GENRES = new HashMap<>();

    static {
        Object[][] t = {{"cmy", 35, 35}, {"hrr", 27, 0}, {"trl", 53, 0}, {"crm", 80, 80}, {"drm", 18, 18},
                {"doc", 99, 99}, {"rma", 10749, 0}, {"scf", 878, 10765}, {"fnt", 14, 10765}, {"act", 28, 10759},
                {"ani", 16, 16}, {"fml", 10751, 10751}, {"rly", 0, 10764}, {"war", 10752, 10768}, {"wsn", 37, 37},
                {"hst", 36, 0}, {"msc", 10402, 0}};
        for (Object[] e : t) {
            int m = (Integer) e[1], v = (Integer) e[2];
            TMDB_GENRES.put((String) e[0], new int[][]{m > 0 ? new int[]{m} : new int[0], v > 0 ? new int[]{v} : new int[0]});
        }
    }

    static String singular(String w) {
        return w.endsWith("ies") && w.length() > 4 ? w.substring(0, w.length() - 3) + "y"
                : w.endsWith("s") && !w.endsWith("ss") && w.length() > 3 ? w.substring(0, w.length() - 1) : w;
    }

    static String titleCase(String s) {
        StringBuilder b = new StringBuilder();
        for (String w : s.split(" ")) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            if (b.length() > 0 && SMALL.contains(w)) b.append(w);
            else if (w.matches("ww[12i]+|ii|uk|us|usa|nyc|la|fbi|cia|ai")) b.append(w.toUpperCase(Locale.UK));
            else b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }

    static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    // ---------------------------------------------------------------- what was said

    /**
     * Of the speech service's guesses at what was said, the one that names something: "Dune" over
     * "June" and "gym", a film millions know over an obscure one. Requests (people, genres, "films
     * like") are taken as heard, and the first guess wins anything close.
     */
    String bestSpoken(List<String> heard, float[] sure) {
        String top = heard.get(0);
        Query q0 = read(top);
        if (!q0.browse() && (q0.person || q0.like || q0.about != null)) return top;
        if (q0.text.isEmpty() || q0.text.split(" ").length > 4) return top;
        int n = Math.min(5, heard.size());
        List<Future<Double>> scores = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Query q = read(heard.get(i));
            scores.add(POOL.submit(() -> q.text.isEmpty() ? 0.0 : titleWeight(q)));
        }
        String best = top;
        double bestScore = -1;
        for (int i = 0; i < n; i++) {
            double sc;
            try {
                sc = scores.get(i).get(4, TimeUnit.SECONDS);
            } catch (Exception e) {
                sc = 0;
            }
            sc += (sure != null && i < sure.length ? sure[i] * 15 : 0) + (i == 0 ? 8 : 0);
            if (sc > bestScore) {
                bestScore = sc;
                best = heard.get(i);
            }
        }
        return best;
    }

    /** How surely a phrase names a real title: an exact name, and how many people know it. */
    double titleWeight(Query q) throws Exception {
        JSONArray edges = popular(new JSONObject().put("searchQuery", q.text), 6, "POPULAR");
        double best = 0;
        for (int i = 0; i < edges.length(); i++) {
            Result r = parse(edges.getJSONObject(i).getJSONObject("node"), mine);
            double m = matchScore(q.text, r.title);
            if (m < 85) continue;
            double known = Math.log10(Math.max(10, r.votes)) * 8 + (western(r) ? 5 : 0);
            best = Math.max(best, (m >= 100 ? 40 : 20) + known);
        }
        return best;
    }

    // ---------------------------------------------------------------- titles

    static final String NODE_FIELDS = " id objectType content(country: $country, language: $language) {"
            + " title originalReleaseYear posterUrl backdrops { backdropUrl } scoring { imdbScore imdbVotes tomatoMeter certifiedFresh }"
            + " productionCountries externalIds { tmdbId } genres { shortName } }"
            + " offers(country: $country, platform: WEB) { monetizationType standardWebURL package { technicalName } }";

    static final String JW_SEARCH = "query S($country: Country!, $language: Language!, $first: Int!, $filter: TitleFilter, $sort: PopularTitlesSorting!) {"
            + " popularTitles(country: $country, filter: $filter, first: $first, sortBy: $sort) {"
            + " edges { node {" + NODE_FIELDS + " } } } }";

    /** JustWatch's "people also watch" for a title, in the UK. */
    static final String JW_SIMILAR = "query M($id: ID!, $country: Country!, $language: Language!) { node(id: $id) { ... on MovieOrShow {"
            + " similarTitlesV2(country: $country, first: 24) { edges { node {" + NODE_FIELDS + " } } } } } }";

    JSONArray popular(JSONObject filter, int first, String sort) throws Exception {
        JSONObject vars = new JSONObject().put("country", "GB").put("language", "en")
                .put("first", first).put("filter", filter).put("sort", sort);
        JSONObject res = Suggestions.post("https://apis.justwatch.com/graphql",
                new JSONObject().put("query", JW_SEARCH).put("variables", vars).toString());
        return res.getJSONObject("data").getJSONObject("popularTitles").getJSONArray("edges");
    }

    JSONArray packages(Suggestions.Provider only) {
        JSONArray a = new JSONArray();
        if (only != null) a.put(only.jwShort);
        else for (Suggestions.Provider p : mine) a.put(p.jwShort);
        return a;
    }

    /** Popular Now: trending, recent, well rated, made for grown-ups, on your services. */
    List<Result> popularNow() throws Exception {
        int thisYear = Calendar.getInstance().get(Calendar.YEAR);
        JSONObject filter = new JSONObject().put("packages", packages(null))
                .put("monetizationTypes", new JSONArray().put("FLATRATE").put("FREE").put("ADS"))
                .put("releaseYear", new JSONObject().put("min", thisYear - 2))
                .put("imdbScore", new JSONObject().put("min", Suggestions.MIN_SCORE))
                .put("excludeGenres", new JSONArray().put("fml").put("ani"))
                .put("productionCountries", new JSONArray(WEST));
        List<Result> out = new ArrayList<>();
        JSONArray edges = popular(filter, 40, "TRENDING");
        for (int i = 0; i < edges.length(); i++) {
            Result r = parse(edges.getJSONObject(i).getJSONObject("node"), mine);
            if (!r.offers.isEmpty()) merge(out, r);
        }
        return out;
    }

    /** A title search, ranked for a UK viewer. */
    List<Result> titles(Query q) throws Exception {
        JSONObject filter = new JSONObject().put("searchQuery", q.text);
        if (q.type != null) filter.put("objectTypes", new JSONArray().put(q.type.equals("tv") ? "SHOW" : "MOVIE"));
        if (q.service != null) filter.put("packages", packages(q.service));
        JSONArray edges = popular(filter, 40, "POPULAR");
        // On that service there's nothing by that name: look everywhere.
        if (edges.length() == 0 && q.service != null) {
            filter.remove("packages");
            edges = popular(filter, 40, "POPULAR");
        }
        // Nothing for a misspelling ("bridgerten"): try again with the end of the last word trimmed,
        // as JustWatch matches the start of words; the word-by-word matching then forgives the typo.
        for (int cut = 1; edges.length() == 0 && cut <= 3; cut++) {
            String[] w = q.text.trim().split(" ");
            String last = w[w.length - 1];
            if (last.length() - cut < 4) break;
            w[w.length - 1] = last.substring(0, last.length() - cut);
            filter.put("searchQuery", String.join(" ", w));
            edges = popular(filter, 40, "POPULAR");
        }
        List<Result> found = new ArrayList<>();
        for (int i = 0; i < edges.length(); i++) merge(found, parse(edges.getJSONObject(i).getJSONObject("node"), mine));
        return rank(q, found);
    }

    /**
     * JustWatch sometimes lists one programme twice (its Christmas special under "&" and "and"):
     * show it once, with every service that has any. Different programmes with one name (The Office
     * from 2001 and from 2005) stay apart.
     */
    static void merge(List<Result> out, Result r) {
        String key = norm(r.title);
        for (Result o : out) {
            if (!norm(o.title).equals(key)) continue;
            boolean same = o.tmdbId > 0 && o.tmdbId == r.tmdbId || o.year != null && o.year.equals(r.year);
            if (!same) continue;
            for (Suggestions.Item it : r.offers) {
                boolean has = false;
                for (Suggestions.Item e : o.offers) has |= e.providerId == it.providerId;
                if (!has) o.offers.add(it);
            }
            o.rentBuy |= r.rentBuy;
            return;
        }
        out.add(r);
    }

    /**
     * Search results as a UK viewer would want them. Each title is scored on how well its name
     * matches what was asked for, whether you can watch it on your services (or rent or buy it here),
     * whether it's a UK, American or European production, the year and kind if they were said, and
     * how well known it is. Titles from elsewhere appear only when their name is exactly what was
     * asked for, and loose matches only when they're Western and watchable here.
     */
    static List<Result> rank(Query q, List<Result> found) {
        List<Result> kept = new ArrayList<>();
        Map<Result, Double> score = new HashMap<>(), match = new HashMap<>();
        for (int i = 0; i < found.size(); i++) {
            Result r = found.get(i);
            double m = matchScore(q.text, r.title);
            // The year was part of the name: Blade Runner 2049, 2001: A Space Odyssey.
            boolean named = q.year > 0 && q.titleYear != null && r.title.contains(String.valueOf(q.year));
            if (named) m = Math.max(m, matchScore(q.titleYear, r.title));
            boolean exact = m >= 100, all = m >= 60;
            boolean west = western(r), mine = !r.offers.isEmpty(), here = mine || r.rentBuy;
            // Not what UK viewers mean by this search, unless it's that title and watched here.
            if (!west && !(exact && (mine || r.votes >= 20000))) continue;
            if (!all && !(west && here)) continue;   // a loose match has to be worth showing
            double sc = m;
            sc += mine ? 18 : r.rentBuy ? 8 : -25;
            sc += west ? 10 : 0;
            // Made here: The Traitors on the BBC before its American and Indian namesakes.
            boolean saysUK = (" " + norm(r.raw.isEmpty() ? r.title : r.raw) + " ").contains(" uk ");
            sc += saysUK ? 8 : r.countries.size() == 1 && r.countries.contains("GB") ? 6 : r.countries.contains("GB") ? 3 : 0;
            // Exactly as typed, small words and all ("The Traitors", not "Traitors").
            if (literal(r.title).replaceAll("(uk|us|usa)$", "").equals(literal(q.text))) sc += 5;
            if (q.service != null) sc += r.on(q.service.id) ? 25 : -10;
            int ry = Suggestions.parseInt(r.year == null ? "" : r.year);
            if (q.year > 0 && !named) {
                // The year picks between versions of the title; it doesn't make a loose match better.
                sc += ry == q.year ? (m >= 85 ? 30 : 4) : Math.abs(ry - q.year) <= 1 ? (m >= 85 ? 12 : 0) : -15;
            }
            if (q.yearMin > 0) sc += ry >= q.yearMin && ry <= q.yearMax ? 15 : -10;
            if (q.type != null) sc += q.type.equals(r.type) ? 12 : -12;
            // Well known beats obscure: a little for its audience size.
            sc += Math.min(10, Math.log10(Math.max(1, r.votes)) * 2);
            sc -= i * 0.5;                            // JustWatch's popularity order
            score.put(r, sc);
            match.put(r, m);
            kept.add(r);
        }
        kept.sort((a, b) -> Double.compare(score.get(b), score.get(a)));
        // When what was asked for is clearly one title (Slow Horses), titles that merely share a
        // word with it (Only Fools and Horses) go: More Like This brings related titles instead.
        if (!kept.isEmpty() && match.get(kept.get(0)) >= 85) {
            kept.removeIf(r -> match.get(r) < 60);
            // An exact title (Dune, The Office): other titles with its words in (Dune Drifter, Office
            // Uprising) only if they're well known next to it; sequels, versions and remakes stay.
            Result top = kept.get(0);
            if (match.get(top) >= 100) {
                double floor = Math.max(3000, top.votes / 50.0);
                kept.removeIf(r -> r != top && match.get(r) < 100 && r.votes < floor);
            }
        }
        List<Result> out = new ArrayList<>();
        int loose = 0;
        for (Result r : kept) {
            if (match.get(r) < 60 && ++loose > 4) continue;
            out.add(r);
            if (out.size() >= MAX) break;
        }
        {
            StringBuilder b = new StringBuilder("\"" + q.text + "\" year=" + q.year + " type=" + q.type + " service="
                    + (q.service == null ? "" : q.service.name) + ":");
            for (int i = 0; i < Math.min(10, out.size()); i++) {
                Result r = out.get(i);
                b.append(String.format(Locale.UK, "\n  %.0f %s (%s) m=%.0f v=%d %s", score.get(r), r.title, r.year,
                        match.get(r), r.votes, r.countries));
            }
            android.util.Log.i("SearchBrain", b.toString());
        }
        return out;
    }

    /** Browsing: "new comedy films on Netflix", "best thrillers", "80s films", "Netflix". */
    List<Result> browse(Query q) throws Exception {
        int thisYear = Calendar.getInstance().get(Calendar.YEAR);
        JSONObject filter = new JSONObject().put("packages", packages(q.service))
                .put("monetizationTypes", new JSONArray().put("FLATRATE").put("FREE").put("ADS"))
                .put("productionCountries", new JSONArray(WEST));
        if (!q.genres.isEmpty()) {
            // JustWatch matches any genre given: ask for the rarest (documentary, for true crime) and
            // check the rest here.
            Genre rare = null;
            String code = null;
            for (String c : RARE) {
                for (Genre g : q.genres) if (rare == null && Arrays.asList(g.codes).contains(c)) rare = g;
                if (rare != null) {
                    code = c;
                    break;
                }
            }
            filter.put("genres", rare == null ? new JSONArray() : rare.all ? new JSONArray().put(code) : new JSONArray(rare.codes));
        }
        if (!q.has("fml") && !q.has("ani")) {
            filter.put("excludeGenres", new JSONArray().put("fml").put("ani"));
        }
        if (q.ages() != null) filter.put("ageCertifications", new JSONArray(Arrays.asList(q.ages())));
        if (q.type != null) filter.put("objectTypes", new JSONArray().put(q.type.equals("tv") ? "SHOW" : "MOVIE"));
        // Unless a time or "best" was asked for, what's popular in it now: trending, from the last
        // few years, as the streaming apps' own genre rows are.
        boolean now = !q.best && q.year == 0 && q.yearMin == 0 && q.yearMax == 0;
        JSONObject years = new JSONObject();
        if (q.year > 0) years.put("min", q.year).put("max", q.year);
        else if (q.fresh) years.put("min", thisYear - 1);
        else if (now) years.put("min", thisYear - 4);
        else if (q.yearMin > 0) years.put("min", q.yearMin);
        if (q.yearMax > 0 && !q.fresh && q.year == 0) years.put("max", q.yearMax);
        if (years.length() > 0) filter.put("releaseYear", years);
        filter.put("imdbScore", new JSONObject().put("min", q.best ? 7.2 : 6.3));
        List<Result> out = new ArrayList<>();
        JSONArray edges = popular(filter, 40, now || q.fresh ? "TRENDING" : "POPULAR");
        if (now && !q.fresh && edges.length() < 12) {
            // Not much new in it (westerns): its favourites from any time after.
            filter.remove("releaseYear");
            JSONArray more = popular(filter, 40, "POPULAR");
            for (int i = 0; i < more.length(); i++) edges.put(more.get(i));
        }
        for (int i = 0; i < edges.length() && out.size() < MAX * 2; i++) {
            Result r = parse(edges.getJSONObject(i).getJSONObject("node"), mine);
            if (r.offers.isEmpty()) continue;
            // Every genre asked for, and one of them what it mainly is (no cartoons for "comedy").
            if (!q.genres.isEmpty()) {
                // (A rating says it's for children; "family" needn't be the first genre listed.)
                boolean main = q.ages() != null;
                for (int k = 0; k < Math.min(2, r.genres.size()); k++) main |= q.has(r.genres.get(k));
                if (!main || !wanted(q, r)) continue;
            }
            merge(out, r);
        }
        return out.size() > MAX ? new ArrayList<>(out.subList(0, MAX)) : out;
    }

    /** The shelf's name for a browse: "New Comedy Films on Netflix", "Popular 80s Films", "Classic Thrillers". */
    static String browseHeading(Query q) {
        boolean classic = q.decade == null && q.yearMax > 0 && q.yearMax < 2000;
        String lead = q.fresh ? "New" : classic ? "Classic" : q.best ? "Best" : "Popular";
        String kind = "tv".equals(q.type) ? "Series" : "movie".equals(q.type) ? "Films" : "";
        String body = genreName(q, kind);
        if (body.isEmpty() && (classic || q.decade != null)) body = "Films and Series";
        if (body.isEmpty() && q.year > 0) body = "Films and Series";
        String s = (lead + " " + (q.decade != null ? q.decade + " " : "") + body).trim();
        if (q.year > 0) s += " of " + q.year;
        if (q.service != null) s += " on " + q.service.name;
        return s;
    }

    /** Titles like the one found: what people who watch it also watch, Western and watchable here. */
    List<Result> similar(Result to) {
        List<Result> out = new ArrayList<>();
        if (to == null || to.id == null) return out;
        try {
            JSONObject vars = new JSONObject().put("id", to.id).put("country", "GB").put("language", "en");
            JSONObject res = Suggestions.post("https://apis.justwatch.com/graphql",
                    new JSONObject().put("query", JW_SIMILAR).put("variables", vars).toString());
            JSONArray edges = res.getJSONObject("data").getJSONObject("node").getJSONObject("similarTitlesV2").getJSONArray("edges");
            for (int i = 0; i < edges.length() && out.size() < MAX; i++) {
                Result r = parse(edges.getJSONObject(i).getJSONObject("node"), mine);
                if (!western(r) || (r.offers.isEmpty() && !r.rentBuy)) continue;
                out.add(r);
            }
        } catch (Exception ignored) {
            // no suggestions this time
        }
        return out;
    }

    // ---------------------------------------------------------------- people

    /** An actor's or director's work, and its shelf's name. */
    static final class Person {
        String name, heading;
        List<Result> works;
    }

    /**
     * An actor's or director's films and series (TMDB), best known first: a person is recognised
     * when the whole name matches someone well known, or when it was asked for ("films with ...").
     */
    Person people(Query q) throws Exception {
        JSONObject r = Suggestions.get(c, "/search/person?query=" + enc(q.text) + "&include_adult=false&language=en-GB");
        JSONArray a = r.optJSONArray("results");
        if (a == null || a.length() == 0) return null;
        JSONObject p = a.getJSONObject(0);
        double pop = p.optDouble("popularity", 0);
        String name = p.optString("name");
        // A single word ("Zendaya") has to be someone really well known to beat a title.
        double needPop = q.person ? 1 : q.text.contains(" ") ? 4 : 12;
        if (matchScore(q.text, name) < (q.person ? 85 : 100) || pop < needPop) return null;
        String dept = p.optString("known_for_department");
        boolean directs = q.directed != null ? q.directed : "Directing".equals(dept) || "Writing".equals(dept);
        JSONObject credits = Suggestions.get(c, "/person/" + p.optInt("id") + "/combined_credits?language=en-GB");
        JSONArray list = credits.optJSONArray(directs ? "crew" : "cast");
        int thisYear = Calendar.getInstance().get(Calendar.YEAR);
        List<JSONObject> works = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject w = list.getJSONObject(i);
            if (directs && !"Director".equals(w.optString("job")) && !"Creator".equals(w.optString("job"))) continue;
            String role = w.optString("character", "");
            if (role.matches("(?i).*(self|himself|herself|narrator|host|presenter|guest).*")) continue;
            JSONArray gids = w.optJSONArray("genre_ids");
            boolean talk = false;
            for (int k = 0; gids != null && k < gids.length(); k++) talk |= gids.optInt(k) == 10767 || gids.optInt(k) == 10763;
            if (talk || w.optInt("vote_count") < 80 || w.isNull("poster_path")) continue;
            String type = w.optString("media_type");
            if (q.type != null && !q.type.equals(type)) continue;
            // A guest spot in a series or a cameo in a film isn't what they're known for.
            if (!directs && ("tv".equals(type) ? w.optInt("episode_count", 99) < 6 : w.optInt("order", 0) > 15)) continue;
            if (!genresMatch(q.genres, gids, "tv".equals(type))) continue;
            int y = Suggestions.parseInt(w.optString("tv".equals(type) ? "first_air_date" : "release_date", "").replaceAll("-.*", ""));
            if (q.yearMin > 0 && (y < q.yearMin || y > q.yearMax) || q.fresh && y < thisYear - 3) continue;
            if (!seen.add(w.optInt("id"))) continue;
            works.add(w);
        }
        if (q.fresh) {
            // Newest first.
            works.sort((x, y) -> date(y).compareTo(date(x)));
        } else if (q.best) {
            // Best rated, among the well known.
            works.sort((x, y) -> Double.compare(y.optDouble("vote_average") * Math.log10(y.optDouble("vote_count")),
                    x.optDouble("vote_average") * Math.log10(x.optDouble("vote_count"))));
        } else {
            // Best known first, leading roles before supporting ones.
            works.sort((x, y) -> Double.compare(fame(y), fame(x)));
        }
        List<Result> out = new ArrayList<>();
        for (JSONObject w : works) {
            Result res = tmdbResult(w);
            res.role = directs ? "Director" : w.optString("character", "");
            out.add(res);
            if (out.size() >= MAX) break;
        }
        if (out.isEmpty()) return null;
        // "Comedies with Will Ferrell", "New Films with Tom Hardy".
        StringBuilder h = new StringBuilder();
        if (q.fresh) h.append("New ");
        else if (q.best) h.append("Best ");
        if (q.decade != null) h.append(q.decade).append(' ');
        h.append(genreName(q, "tv".equals(q.type) ? "Series" : "movie".equals(q.type) ? "Films" : ""));
        // "Starring Tom Hardy", "Comedies with Will Ferrell", "Films Directed by Greta Gerwig".
        String lead = h.toString().trim();
        Person found = new Person();
        found.name = name;
        found.works = out;
        found.heading = lead.isEmpty() ? (directs ? "Directed by " : "Starring ") + name
                : lead + (directs ? " Directed by " : " with ") + name;
        return found;
    }

    static double fame(JSONObject w) {
        double lead = w.has("episode_count") ? (w.optInt("episode_count") >= 10 ? 1 : 0.5)
                : w.optInt("order", 0) <= 2 ? 1 : w.optInt("order", 0) <= 6 ? 0.6 : 0.3;
        return Math.pow(w.optDouble("vote_count", 1), 0.8) * Math.pow(w.optDouble("popularity", 1), 0.3) * lead;
    }

    static String date(JSONObject w) {
        return w.optString("release_date", w.optString("first_air_date", ""));
    }

    static boolean genresMatch(List<Genre> gs, JSONArray gids, boolean tv) {
        List<String> have = new ArrayList<>();
        for (int k = 0; gids != null && k < gids.length(); k++) have.add(String.valueOf(gids.optInt(k)));
        for (Genre g : gs) {
            List<String> ids = tmdbIds(g, tv);
            if (ids.isEmpty()) continue;
            int n = 0;
            for (String id : ids) if (have.contains(id)) n++;
            if (g.all ? n < ids.size() : n == 0) return false;
        }
        return true;
    }

    /** A TMDB film or series (from search, credits or discover) as a result; its page is found on open. */
    static Result tmdbResult(JSONObject w) {
        Result res = new Result();
        boolean tv = "tv".equals(w.optString("media_type"));
        res.type = tv ? "tv" : "movie";
        res.title = Suggestions.cleanTitle(w.optString(tv ? "name" : "title"));
        String date = w.optString(tv ? "first_air_date" : "release_date", "");
        res.year = date.length() >= 4 ? date.substring(0, 4) : "";
        res.tmdbId = w.optInt("id");
        res.poster = "https://image.tmdb.org/t/p/w342" + w.optString("poster_path");
        if (!w.isNull("backdrop_path")) res.backdrop = "https://image.tmdb.org/t/p/w1280" + w.optString("backdrop_path");
        return res;
    }

    /**
     * A title found through TMDB (an actor's film) as a JustWatch title, for its page: searched by
     * name and matched by TMDB id, else the closest name. Off the main thread.
     */
    Result resolve(Result t) {
        try {
            Query q = new Query();
            q.text = t.title;
            q.type = t.type;
            JSONObject filter = new JSONObject().put("searchQuery", t.title)
                    .put("objectTypes", new JSONArray().put("tv".equals(t.type) ? "SHOW" : "MOVIE"));
            JSONArray edges = popular(filter, 10, "POPULAR");
            Result best = null;
            for (int i = 0; i < edges.length(); i++) {
                Result r = parse(edges.getJSONObject(i).getJSONObject("node"), mine);
                if (r.tmdbId == t.tmdbId) return r;
                if (best == null && matchScore(t.title, r.title) >= 100) best = r;
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- one title

    /** One JustWatch title as a result: its art, year, scores, genres, where it's made, and your services' offers. */
    static Result parse(JSONObject n, List<Suggestions.Provider> mine) throws Exception {
        JSONObject ct = n.getJSONObject("content");
        Result r = new Result();
        r.raw = ct.optString("title");
        r.title = Suggestions.cleanTitle(r.raw);
        r.id = n.optString("id");
        JSONArray bds = ct.optJSONArray("backdrops");
        if (bds != null && bds.length() > 0) {
            r.backdrop = "https://images.justwatch.com" + bds.getJSONObject(0).optString("backdropUrl")
                    .replace("{profile}", "s1920").replace("{format}", "jpg");
        }
        int y = ct.optInt("originalReleaseYear", 0);
        r.year = y > 0 ? String.valueOf(y) : "";
        JSONObject ext = ct.optJSONObject("externalIds");
        r.tmdbId = ext == null ? 0 : Suggestions.parseInt(ext.optString("tmdbId"));
        JSONArray pc = ct.optJSONArray("productionCountries");
        for (int k = 0; pc != null && k < pc.length(); k++) r.countries.add(pc.optString(k));
        JSONArray gs = ct.optJSONArray("genres");
        for (int k = 0; gs != null && k < gs.length(); k++) r.genres.add(gs.getJSONObject(k).optString("shortName"));
        JSONObject sc = ct.optJSONObject("scoring");
        if (sc != null) {
            r.imdb = sc.optDouble("imdbScore", 0);
            r.votes = sc.optInt("imdbVotes", 0);
            if (!sc.isNull("tomatoMeter")) r.tomato = sc.optInt("tomatoMeter", -1);
            r.certified = sc.optBoolean("certifiedFresh", false);
        }
        r.type = "SHOW".equals(n.optString("objectType")) ? "tv" : "movie";
        String poster = ct.optString("posterUrl", "");
        r.poster = poster.isEmpty() ? null
                : "https://images.justwatch.com" + poster.replace("{profile}", "s332").replace("{format}", "jpg");
        JSONArray offers = n.optJSONArray("offers");
        for (Suggestions.Provider p : mine) {
            for (int k = 0; offers != null && k < offers.length(); k++) {
                JSONObject o = offers.getJSONObject(k);
                String m = o.optString("monetizationType");
                if (!p.jwTech.equals(o.getJSONObject("package").optString("technicalName"))) continue;
                if (!m.equals("FLATRATE") && !m.equals("FREE") && !m.equals("ADS")) continue;
                Suggestions.Item it = new Suggestions.Item();
                it.title = r.title;
                it.type = r.type;
                it.providerId = p.id;
                it.link = o.optString("standardWebURL");
                r.offers.add(it);
                break;
            }
        }
        for (int k = 0; offers != null && k < offers.length(); k++) {
            String m = offers.getJSONObject(k).optString("monetizationType");
            if (m.equals("RENT") || m.equals("BUY")) r.rentBuy = true;
        }
        return r;
    }

    // ---------------------------------------------------------------- matching words

    static final Set<String> SMALL = new HashSet<>(Arrays.asList(
            "the", "a", "an", "and", "of", "in", "on", "to", "for", "with"));

    /** One spelling for words the UK and US spell differently (neighbour/neighbor, colour, centre). */
    static String fold(String w) {
        if (w.length() > 4 && w.contains("our") && !w.startsWith("our") && !w.equals("tour") && !w.equals("hour")) {
            w = w.replace("our", "or");
        }
        if (w.endsWith("tre") && w.length() > 4) w = w.substring(0, w.length() - 3) + "ter";
        if (w.endsWith("ise") && w.length() > 5) w = w.substring(0, w.length() - 3) + "ize";
        return w;
    }

    static final Map<String, String> NUMBERS = new HashMap<>();

    static {
        String[] n = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten"};
        for (int i = 0; i < n.length; i++) NUMBERS.put(n[i], String.valueOf(i));
    }

    static String literal(String s) {
        return s.toLowerCase(Locale.UK).replaceAll("[^a-z0-9]", "");
    }

    static List<String> keyWords(String s) {
        List<String> out = new ArrayList<>();
        for (String w : norm(s).split(" ")) {
            if (w.isEmpty() || SMALL.contains(w)) continue;
            out.add(fold(NUMBERS.containsKey(w) ? NUMBERS.get(w) : w));
        }
        if (out.isEmpty()) for (String w : norm(s).split(" ")) if (!w.isEmpty()) out.add(fold(w));
        return out;
    }

    /**
     * The same word, allowing for a typo ("bridgerten"): 2. The start of it, as the last word being
     * typed ("neighbo"): 1, which can't make an exact title (Dune isn't The Dunes). Otherwise 0.
     */
    static int sameWord(String a, String b, boolean last) {
        if (a.equals(b)) return 2;
        if (a.length() >= 5 && b.length() >= 5 && edits(a, b) <= (Math.min(a.length(), b.length()) >= 8 ? 2 : 1)) return 2;
        return last && a.length() >= 3 && b.startsWith(a) ? 1 : 0;
    }

    static int edits(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                cur[j] = Math.min(Math.min(cur[j - 1], prev[j]) + 1, prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    /**
     * How well a title matches what was asked for, by its words rather than its letters: 100 when
     * they're the same title (spelling and small words aside), 85 when every word asked for starts
     * the title in order, 65 when every word is somewhere in it, and less the fewer words it shares.
     * Spoken words count as one: "spider man" is "Spider-Man", "two" is "2".
     */
    static double matchScore(String q, String title) {
        List<String> qw = keyWords(q), tw = keyWords(title);
        if (qw.isEmpty() || tw.isEmpty()) return 0;
        // "The Office (US)", "The Traitors: UK": where it's from isn't part of its name.
        String last = tw.get(tw.size() - 1);
        if (tw.size() > 1 && (last.equals("uk") || last.equals("us") || last.equals("usa")) && !qw.contains(last)) {
            tw = new ArrayList<>(tw.subList(0, tw.size() - 1));
        }
        // "spiderman" for "Spider-Man", "spider man" for "Spiderman".
        String qj = String.join("", qw), tj = String.join("", tw);
        if (qj.equals(tj)) return 100;
        int hit = 0, pos = 0;
        boolean inOrder = true, begun = false;
        boolean[] used = new boolean[tw.size()];
        for (int i = 0; i < qw.size(); i++) {
            int found = -1;
            for (int j = 0; j < tw.size(); j++) {
                int same = used[j] ? 0 : sameWord(qw.get(i), tw.get(j), i == qw.size() - 1);
                if (same > 0) {
                    found = j;
                    begun |= same == 1;
                    break;
                }
            }
            if (found < 0) continue;
            used[found] = true;
            hit++;
            if (found != pos) inOrder = false;
            pos = found + 1;
        }
        double qCov = hit / (double) qw.size();
        if (qCov < 1) return tj.startsWith(qj) && qj.length() >= 4 ? 85 : 40 * qCov;
        if (hit == tw.size()) return begun ? 85 : 100;
        return inOrder ? 85 : 65;
    }

    static final Set<String> WEST = new HashSet<>(Arrays.asList(
            "GB", "IE", "US", "CA", "AU", "NZ", "FR", "DE", "ES", "IT", "NL", "BE", "LU", "SE", "NO", "DK", "FI",
            "IS", "AT", "CH", "PT", "PL", "CZ"));

    static boolean western(Result r) {
        if (r.countries.isEmpty()) return false;
        for (String c : r.countries) if (WEST.contains(c)) return true;
        return false;
    }

    /** Lower case, no punctuation, a leading "the" and trailing year dropped: for matching titles. */
    static String norm(String t) {
        String s = t.toLowerCase(Locale.UK).replaceAll("\\(\\d{4}\\)", " ").replace("&", " and ").replaceAll("['\u2019]", "")
                .replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        return s.startsWith("the ") ? s.substring(4) : s;
    }
}
