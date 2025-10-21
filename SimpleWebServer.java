import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SimpleWebServer {
    // ===== In-memory state =====
    private static final List<User> USERS = Collections.synchronizedList(new ArrayList<>());

    // targetEmail(lowercase) -> total coins on their head (from opened contracts)
    private static final ConcurrentHashMap<String, Integer> CONTRACT_POT = new ConcurrentHashMap<>();

    // actorEmail(lowercase) -> targetEmail(lowercase) that is currently the +2 bounty
    private static final ConcurrentHashMap<String, String> CURRENT_BOUNTY = new ConcurrentHashMap<>();

    // victim(lowercase) -> excuminicados list (most-recent first) — per-victim history
    private static final ConcurrentHashMap<String, Deque<Excum>> EXCUMINICADOS = new ConcurrentHashMap<>();

    // victim(lowercase) -> pending kill submissions awaiting victim decision (most-recent first)
    private static final ConcurrentHashMap<String, Deque<KillSubmission>> PENDING_KILLS = new ConcurrentHashMap<>();

    // ===== Global master list of kills (most-recent first) =====
    private static final Deque<GlobalKill> ALL_KILLS = new ArrayDeque<>();

    // ===== Models =====
    private static class User {
        final String first, last, email, img;
        String password;
        int value;   // wanted level
        int coins;   // wallet
        User(String f, String l, String e, String img, String p){
            this.first=f; this.last=l; this.email=e; this.img=img; this.password=p;
            this.value=10; this.coins=3;
        }
    }

    // Per-victim feed item
    private static class Excum {
        final String actorEmail;   // who claimed the kill
        final long ts;
        final String imageDataUrl; // submitted image (data URL)
        final String status;       // "pending", "confirmed", "denied"
        Excum(String actorEmail, long ts, String imageDataUrl, String status){
            this.actorEmail = actorEmail; this.ts = ts; this.imageDataUrl = imageDataUrl; this.status = status;
        }
    }

    // Pending kill submission for a victim to review
    private static class KillSubmission {
        final String id; // uuid
        final String actorEmail;
        final String targetEmail;
        final String imageDataUrl; // base64 data URL from client
        final long ts;
        volatile String status; // "pending", then "confirmed" or "denied"
        KillSubmission(String actor, String target, String img){
            this.id = UUID.randomUUID().toString();
            this.actorEmail = actor;
            this.targetEmail = target;
            this.imageDataUrl = img;
            this.ts = System.currentTimeMillis();
            this.status = "pending";
        }
    }

    // Global feed item
    private static class GlobalKill {
        final String id;              // same id as KillSubmission
        final String actorEmail;      // who claimed the kill
        final String victimEmail;     // who they claimed to kill
        final long ts;                // when submitted
        final String imageDataUrl;    // evidence
        volatile String status;       // "pending" | "confirmed" | "denied"
        GlobalKill(String id, String actorEmail, String victimEmail, long ts, String imageDataUrl, String status) {
            this.id = id; this.actorEmail = actorEmail; this.victimEmail = victimEmail;
            this.ts = ts; this.imageDataUrl = imageDataUrl; this.status = status;
        }
    }

    public static void main(String[] args) throws IOException {
        // >>> CHANGED: dynamic port + bind to 0.0.0.0 (required by Render/Railway/Fly)
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        InetSocketAddress addr = new InetSocketAddress("0.0.0.0", port);
        HttpServer server = HttpServer.create(addr, 0);
        // <<<

        // ===== Static pages =====
        serveFile(server, "/", "index.html");
        serveFile(server, "/login", "login.html");
        serveFile(server, "/signup", "signup.html");
        serveFile(server, "/home", "home.html");
        serveFile(server, "/contracts", "contracts.html");
        serveFile(server, "/commerce", "commerce.html");
        serveFile(server, "/kill-submit", "kill_submit.html"); // evidence submit page

        // ===== Auth =====
        server.createContext("/signup-post", ex -> {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { redirect(ex, "/signup"); return; }
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String,String> f = parseForm(body);
            String first=f.getOrDefault("first","").trim();
            String last =f.getOrDefault("last","").trim();
            String email=f.getOrDefault("email","").trim();
            String pass =f.getOrDefault("password","");
            String img  =f.getOrDefault("img","").trim();
            if(first.isEmpty()||last.isEmpty()||email.isEmpty()||pass.isEmpty()){ redirect(ex,"/signup?error=missing"); return; }
            synchronized (USERS){
                boolean exists = USERS.stream().anyMatch(u->u.email.equalsIgnoreCase(email));
                if(!exists){
                    String avatar = img.isEmpty()
                            ? "https://api.dicebear.com/7.x/initials/svg?seed="+
                            java.net.URLEncoder.encode(first+" "+last, StandardCharsets.UTF_8)
                            : img;
                    USERS.add(new User(first,last,email,avatar,pass));
                }
            }
            setSession(ex, email);
            redirect(ex,"/home");
        });

        server.createContext("/login-post", ex -> {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { redirect(ex, "/login"); return; }
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String,String> f = parseForm(body);
            String email=f.getOrDefault("email","").trim();
            String pass =f.getOrDefault("password","");
            User u = findUser(email);
            if(u==null || !u.password.equals(pass)){ redirect(ex,"/login?error=bad"); return; }
            setSession(ex, email);
            redirect(ex,"/home");
        });

        server.createContext("/logout", ex -> { clearSession(ex); redirect(ex,"/"); });

        // ===== APIs used by HOME and others =====

        // Snapshot for home (+ expose: pending targets I’ve submitted; and if there’s incoming pending against me)
        server.createContext("/api/users", ex -> {
            String me = getSessionEmail(ex);
            if (me == null) { json(ex,401,"{\"ok\":false,\"error\":\"unauthorized\"}"); return; }

            List<User> snapshot;
            synchronized (USERS) { snapshot = new ArrayList<>(USERS); }
            snapshot.sort((a,b)->Integer.compare(b.value, a.value));

            // Ensure a bounty target for me (not self)
            String targetEmail = CURRENT_BOUNTY.get(me);
            if (targetEmail == null) {
                List<User> others = new ArrayList<>();
                for (User u : snapshot) if (!u.email.equalsIgnoreCase(me)) others.add(u);
                if (!others.isEmpty()) {
                    User t = others.get(new Random().nextInt(others.size()));
                    CURRENT_BOUNTY.put(me, t.email.toLowerCase(Locale.ROOT));
                    targetEmail = t.email.toLowerCase(Locale.ROOT);
                }
            }

            // Collect pending submissions that I (actor=me) have made and are still pending
            Set<String> pendingTargets = new HashSet<>();
            for (Map.Entry<String, Deque<KillSubmission>> e : PENDING_KILLS.entrySet()) {
                for (KillSubmission s : e.getValue()) {
                    if ("pending".equals(s.status) && s.actorEmail.equalsIgnoreCase(me)) {
                        pendingTargets.add(s.targetEmail.toLowerCase(Locale.ROOT));
                    }
                }
            }

            // NEW: detect if there's a pending kill against me (blocks submitting new kills)
            boolean incomingPending = false;
            {
                Deque<KillSubmission> inc = PENDING_KILLS.getOrDefault(me.toLowerCase(Locale.ROOT), new ArrayDeque<>());
                for (KillSubmission s : inc) {
                    if ("pending".equals(s.status)) { incomingPending = true; break; }
                }
            }

            StringBuilder sb = new StringBuilder();
            sb.append("{\"ok\":true,\"me\":\"").append(escape(me)).append("\",");
            if (targetEmail != null) sb.append("\"target\":\"").append(escape(targetEmail)).append("\",");
            // include my pending targets
            sb.append("\"pending\":[");
            int pi=0; for (String p : pendingTargets){ if (pi++>0) sb.append(','); sb.append("\"").append(escape(p)).append("\""); }
            sb.append("],");
            // NEW: include incomingPending
            sb.append("\"incomingPending\":").append(incomingPending).append(",");

            sb.append("\"users\":[");
            for (int i=0;i<snapshot.size();i++){
                if(i>0) sb.append(',');
                User u = snapshot.get(i);
                int pot = CONTRACT_POT.getOrDefault(u.email.toLowerCase(Locale.ROOT),0);
                sb.append("{\"first\":\"").append(escape(u.first)).append("\",")
                        .append("\"last\":\"").append(escape(u.last)).append("\",")
                        .append("\"email\":\"").append(escape(u.email)).append("\",")
                        .append("\"img\":\"").append(escape(u.img)).append("\",")
                        .append("\"value\":").append(u.value).append(",")
                        .append("\"coins\":").append(u.coins).append(",")
                        .append("\"worth\":").append(pot).append("}");
            }
            sb.append("]}");
            json(ex,200,sb.toString());
        });

        // Targets for contract (exclude self & current bounty)
        server.createContext("/api/targets-for-contract", ex -> {
            String me = getSessionEmail(ex);
            if (me == null) { json(ex,401,"{\"ok\":false,\"error\":\"unauthorized\"}"); return; }
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { json(ex,405,"{\"ok\":false,\"error\":\"method\"}"); return; }

            String assigned = CURRENT_BOUNTY.get(me); // lowercase email or null
            List<User> snapshot;
            synchronized (USERS) { snapshot = new ArrayList<>(USERS); }

            StringBuilder sb = new StringBuilder("{\"ok\":true,\"targets\":[");
            boolean first=true;
            for (User u : snapshot){
                boolean isSelf = u.email.equalsIgnoreCase(me);
                boolean isCurrent = assigned != null && u.email.equalsIgnoreCase(assigned);
                if (isSelf || isCurrent) continue;
                int pot = CONTRACT_POT.getOrDefault(u.email.toLowerCase(Locale.ROOT), 0);
                if(!first) sb.append(',');
                first=false;
                sb.append("{\"first\":\"").append(escape(u.first)).append("\",")
                        .append("\"last\":\"").append(escape(u.last)).append("\",")
                        .append("\"email\":\"").append(escape(u.email)).append("\",")
                        .append("\"img\":\"").append(escape(u.img)).append("\",")
                        .append("\"value\":").append(u.value).append(",")
                        .append("\"worth\":").append(pot).append("}");
            }
            sb.append("]}");
            json(ex,200,sb.toString());
        });

        // Open a standard contract (+1 to target’s pot; costs 1 coin; cannot be your current bounty)
        server.createContext("/api/contract/standard", ex -> {
            String me = getSessionEmail(ex);
            if (me == null) { json(ex,401,"{\"ok\":false,\"error\":\"unauthorized\"}"); return; }
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { json(ex,405,"{\"ok\":false,\"error\":\"method\"}"); return; }

            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String,String> f = parseForm(body);
            String targetEmail = f.getOrDefault("target","").trim();

            synchronized (USERS) {
                User actor = findUser(me);
                User target = findUser(targetEmail);
                if (actor == null || target == null || actor.email.equalsIgnoreCase(target.email)) {
                    json(ex,400,"{\"ok\":false,\"error\":\"invalid_target\"}"); return;
                }
                String current = CURRENT_BOUNTY.get(me);
                if (current != null && target.email.equalsIgnoreCase(current)) {
                    json(ex,400,"{\"ok\":false,\"error\":\"is_current_bounty\"}"); return;
                }
                if (actor.coins < 1) { json(ex,400,"{\"ok\":false,\"error\":\"no_coins\"}"); return; }

                actor.coins -= 1;
                String tkey = target.email.toLowerCase(Locale.ROOT);
                CONTRACT_POT.merge(tkey, 1, Integer::sum);

                json(ex,200,"{\"ok\":true,\"coins\":"+actor.coins+"}");
            }
        });

        // ===== Picture-verified kill flow =====

        // 1) Submit a kill (attacker) -> creates a pending record for victim review
        // POST fields: target=<email>, image=<dataURL>
        server.createContext("/api/kill/submit", ex -> {
            String actorEmail = getSessionEmail(ex);
            if (actorEmail == null) { json(ex,401,"{\"ok\":false,\"error\":\"unauthorized\"}"); return; }
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { json(ex,405,"{\"ok\":false,\"error\":\"method\"}"); return; }

            // === NEW: Block if there's a pending kill against the actor ===
            String actorVictimKey = actorEmail.toLowerCase(Locale.ROOT);
            Deque<KillSubmission> incomingOnActor = PENDING_KILLS.getOrDefault(actorVictimKey, new ArrayDeque<>());
            for (KillSubmission s : incomingOnActor) {
                if ("pending".equals(s.status)) {
                    json(ex, 400, "{\"ok\":false,\"error\":\"pending_against_you\"}");
                    return;
                }
            }

            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String,String> f = parseForm(body);
            String targetEmail = f.getOrDefault("target","").trim();
            String imageDataUrl = f.getOrDefault("image","").trim();

            User actor = findUser(actorEmail);
            User target = findUser(targetEmail);
            if (actor==null || target==null || actor.email.equalsIgnoreCase(target.email)) {
                json(ex,400,"{\"ok\":false,\"error\":\"invalid_target\"}"); return;
            }
            if (imageDataUrl.isEmpty()) {
                json(ex,400,"{\"ok\":false,\"error\":\"missing_image\"}"); return;
            }

            String victimKey = target.email.toLowerCase(Locale.ROOT);

            // Prevent duplicate pending submissions for same actor->target
            Deque<KillSubmission> q = PENDING_KILLS.computeIfAbsent(victimKey, k -> new ArrayDeque<>());
            for (KillSubmission s : q) {
                if ("pending".equals(s.status) && s.actorEmail.equalsIgnoreCase(actor.email) &&
                        s.targetEmail.equalsIgnoreCase(target.email)) {
                    json(ex,200,"{\"ok\":true,\"alreadyPending\":true}");
                    return;
                }
            }

            KillSubmission sub = new KillSubmission(actor.email, target.email, imageDataUrl);
            q.addFirst(sub);

            // Also show in victim’s per-user list right away as "pending"
            EXCUMINICADOS.computeIfAbsent(victimKey, k -> new ArrayDeque<>())
                    .addFirst(new Excum(actor.email, sub.ts, imageDataUrl, "pending"));

            // Add to global master list as pending
            synchronized (ALL_KILLS) {
                ALL_KILLS.addFirst(new GlobalKill(
                        sub.id, actor.email, target.email, sub.ts, imageDataUrl, "pending"
                ));
            }

            json(ex,200,"{\"ok\":true,\"id\":\""+escape(sub.id)+"\"}");
        });

        // 2) Victim fetches pending submissions to review
        server.createContext("/api/kill/review", ex -> {
            String me = getSessionEmail(ex);
            if (me == null) { json(ex,401,"{\"ok\":false,\"error\":\"unauthorized\"}"); return; }
            Deque<KillSubmission> q = PENDING_KILLS.getOrDefault(me.toLowerCase(Locale.ROOT), new ArrayDeque<>());
            StringBuilder sb = new StringBuilder("{\"ok\":true,\"items\":[");
            boolean first = true;
            for (KillSubmission s : q) {
                if (!"pending".equals(s.status)) continue;
                if (!first) sb.append(','); first=false;
                sb.append("{\"id\":\"").append(escape(s.id)).append("\",")
                        .append("\"actor\":\"").append(escape(s.actorEmail)).append("\",")
                        .append("\"target\":\"").append(escape(s.targetEmail)).append("\",")
                        .append("\"image\":\"").append(escape(s.imageDataUrl)).append("\",")
                        .append("\"ts\":").append(s.ts).append("}");
            }
            sb.append("]}");
            json(ex,200,sb.toString());
        });

        // 3) Victim decides: confirm or deny
        // POST: id=<id>, decision=confirm|deny
        server.createContext("/api/kill/decision", ex -> {
            String me = getSessionEmail(ex);
            if (me == null) { json(ex,401,"{\"ok\":false,\"error\":\"unauthorized\"}"); return; }
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { json(ex,405,"{\"ok\":false,\"error\":\"method\"}"); return; }

            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String,String> f = parseForm(body);
            String id = f.getOrDefault("id","").trim();
            String decision = f.getOrDefault("decision","").trim().toLowerCase(Locale.ROOT);
            if (id.isEmpty() || !(decision.equals("confirm") || decision.equals("deny"))) {
                json(ex,400,"{\"ok\":false,\"error\":\"bad_request\"}"); return;
            }

            String victimKey = me.toLowerCase(Locale.ROOT);
            Deque<KillSubmission> q = PENDING_KILLS.getOrDefault(victimKey, new ArrayDeque<>());
            KillSubmission sub = null;
            for (KillSubmission s : q) { if (s.id.equals(id) && "pending".equals(s.status)) { sub = s; break; } }
            if (sub == null) { json(ex,404,"{\"ok\":false,\"error\":\"not_found\"}"); return; }

            sub.status = decision.equals("confirm") ? "confirmed" : "denied";

            // Update the most recent matching "pending" excum entry to the final status
            Deque<Excum> exList = EXCUMINICADOS.getOrDefault(victimKey, new ArrayDeque<>());
            for (Excum e : new ArrayList<>(exList)) {
                if (e.ts == sub.ts && e.actorEmail.equalsIgnoreCase(sub.actorEmail) && "pending".equals(e.status)) {
                    exList.remove(e);
                    exList.addFirst(new Excum(e.actorEmail, e.ts, e.imageDataUrl, sub.status));
                    break;
                }
            }

            // Update global master list status by id
            synchronized (ALL_KILLS) {
                for (GlobalKill g : ALL_KILLS) {
                    if (g.id.equals(id) && "pending".equals(g.status)) {
                        g.status = decision.equals("confirm") ? "confirmed" : "denied";
                        break;
                    }
                }
            }

            if ("deny".equals(decision)) {
                json(ex,200,"{\"ok\":true,\"status\":\"denied\"}");
                return;
            }

            // Confirm: apply original kill effects
            synchronized (USERS){
                User actor = findUser(sub.actorEmail);
                User target = findUser(sub.targetEmail);
                if (actor==null || target==null) { json(ex,400,"{\"ok\":false,\"error\":\"missing_user\"}"); return; }

                String bounty = CURRENT_BOUNTY.get(sub.actorEmail); // lowercase
                boolean isBountyKill = bounty != null && target.email.equalsIgnoreCase(bounty);
                String tkey = target.email.toLowerCase(Locale.ROOT);
                int potOnTarget = isBountyKill ? CONTRACT_POT.getOrDefault(tkey,0) : 0;

                int dec = 1 + potOnTarget; // base 1 + pot if bounty kill
                if (target.value - dec < 1) dec = Math.max(0, target.value - 1);

                target.value -= dec;
                actor.value  += isBountyKill ? 2 : 1;

                if (isBountyKill && potOnTarget > 0) {
                    actor.coins += potOnTarget;
                    CONTRACT_POT.put(tkey, 0);
                }

                // roll a new bounty for actor
                List<User> candidates = new ArrayList<>();
                for (User u : USERS) if (!u.email.equalsIgnoreCase(actor.email)) candidates.add(u);
                if (!candidates.isEmpty()){
                    User newT = candidates.get(new Random().nextInt(candidates.size()));
                    CURRENT_BOUNTY.put(actor.email.toLowerCase(Locale.ROOT), newT.email.toLowerCase(Locale.ROOT));
                } else {
                    CURRENT_BOUNTY.remove(actor.email.toLowerCase(Locale.ROOT));
                }
            }

            json(ex,200,"{\"ok\":true,\"status\":\"confirmed\"}");
        });

        // Per-user excuminicados (if you still use it anywhere)
        server.createContext("/api/excuminicados", ex -> {
            String me = getSessionEmail(ex);
            if (me == null) { json(ex,401,"{\"ok\":false,\"error\":\"unauthorized\"}"); return; }
            Deque<Excum> list = EXCUMINICADOS.getOrDefault(me.toLowerCase(Locale.ROOT), new ArrayDeque<>());
            StringBuilder sb = new StringBuilder("{\"ok\":true,\"items\":[");
            boolean first=true;
            for (Excum k : list){
                User a = findUser(k.actorEmail);
                if(!first) sb.append(','); first=false;
                sb.append("{\"first\":\"").append(escape(a!=null?a.first:"")).append("\",")
                        .append("\"last\":\"").append(escape(a!=null?a.last :"")).append("\",")
                        .append("\"email\":\"").append(escape(a!=null?a.email:"")).append("\",")
                        .append("\"img\":\"").append(escape(a!=null?a.img  :"")).append("\",")
                        .append("\"evidence\":\"").append(escape(k.imageDataUrl)).append("\",")
                        .append("\"status\":\"").append(escape(k.status)).append("\",")
                        .append("\"ts\":").append(k.ts).append("}");
            }
            sb.append("]}");
            json(ex,200,sb.toString());
        });

        // Global "Excommunicados" feed — all kills
        server.createContext("/api/kills", ex -> {
            String me = getSessionEmail(ex);
            if (me == null) { json(ex, 401, "{\"ok\":false,\"error\":\"unauthorized\"}"); return; }
            StringBuilder sb = new StringBuilder("{\"ok\":true,\"items\":[");
            boolean first = true;
            synchronized (ALL_KILLS) {
                for (GlobalKill k : ALL_KILLS) {
                    User actor = findUser(k.actorEmail);
                    User victim = findUser(k.victimEmail);
                    if (!first) sb.append(','); first = false;
                    sb.append("{")
                            .append("\"id\":\"").append(escape(k.id)).append("\",")
                            .append("\"status\":\"").append(escape(k.status)).append("\",")
                            .append("\"ts\":").append(k.ts).append(",")
                            .append("\"evidence\":\"").append(escape(k.imageDataUrl)).append("\",")

                            .append("\"actor\":{")
                            .append("\"first\":\"").append(escape(actor!=null?actor.first:"")).append("\",")
                            .append("\"last\":\"").append(escape(actor!=null?actor.last:"")).append("\",")
                            .append("\"email\":\"").append(escape(actor!=null?actor.email:"")).append("\",")
                            .append("\"img\":\"").append(escape(actor!=null?actor.img:"")).append("\"")
                            .append("},")

                            .append("\"victim\":{")
                            .append("\"first\":\"").append(escape(victim!=null?victim.first:"")).append("\",")
                            .append("\"last\":\"").append(escape(victim!=null?victim.last:"")).append("\",")
                            .append("\"email\":\"").append(escape(victim!=null?victim.email:"")).append("\",")
                            .append("\"img\":\"").append(escape(victim!=null?victim.img:"")).append("\"")
                            .append("}")

                            .append("}");
                }
            }
            sb.append("]}");
            json(ex, 200, sb.toString());
        });

        // ===== Commerce APIs (unchanged logic) =====
        server.createContext("/api/commerce/summary", ex -> {
            String me = getSessionEmail(ex);
            if (me == null) { json(ex,401,"{\"ok\":false,\"error\":\"unauthorized\"}"); return; }
            User self = findUser(me);
            if (self == null) { json(ex,404,"{\"ok\":false,\"error\":\"missing_user\"}"); return; }

            String assigned = CURRENT_BOUNTY.get(me);
            int myPot = CONTRACT_POT.getOrDefault(me.toLowerCase(Locale.ROOT), 0);

            List<User> snapshot;
            synchronized (USERS) { snapshot = new ArrayList<>(USERS); }

            StringBuilder sb = new StringBuilder();
            sb.append("{\"ok\":true,");
            sb.append("\"me\":\"").append(escape(me)).append("\",");
            sb.append("\"coins\":").append(self.coins).append(",");
            sb.append("\"myPot\":").append(myPot).append(",");
            sb.append("\"assigned\":\"").append(escape(assigned==null?"":assigned)).append("\",");
            sb.append("\"targets\":[");
            boolean first=true;
            for (User u : snapshot){
                if (u.email.equalsIgnoreCase(me)) continue;
                if(!first) sb.append(',');
                first=false;
                sb.append("{\"first\":\"").append(escape(u.first)).append("\",")
                        .append("\"last\":\"").append(escape(u.last)).append("\",")
                        .append("\"email\":\"").append(escape(u.email)).append("\",")
                        .append("\"img\":\"").append(escape(u.img)).append("\"}");
            }
            sb.append("]}");
            json(ex,200,sb.toString());
        });

        server.createContext("/api/commerce/payoff-self", ex -> {
            String me = getSessionEmail(ex);
            if (me == null) { json(ex,401,"{\"ok\":false,\"error\":\"unauthorized\"}"); return; }
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { json(ex,405,"{\"ok\":false,\"error\":\"method\"}"); return; }

            String key = me.toLowerCase(Locale.ROOT);
            User self = findUser(me);
            int pot = CONTRACT_POT.getOrDefault(key,0);
            if (pot <= 0) { json(ex,400,"{\"ok\":false,\"error\":\"no_pot\"}"); return; }
            if (self.coins <= 0) { json(ex,400,"{\"ok\":false,\"error\":\"no_coins\"}"); return; }

            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String,String> f = parseForm(body);
            String mode = f.getOrDefault("mode","").trim();
            String amountStr = f.getOrDefault("amount","").trim();

            int pay;
            if ("all".equalsIgnoreCase(mode)) {
                pay = Math.min(self.coins, pot);
            } else if (!amountStr.isEmpty()) {
                int a;
                try { a = Integer.parseInt(amountStr); } catch(Exception e){ a = 1; }
                if (a < 1) a = 1;
                pay = Math.min(a, Math.min(self.coins, pot));
            } else {
                pay = 1;
            }
            if (pay <= 0) { json(ex,400,"{\"ok\":false,\"error\":\"invalid_amount\"}"); return; }

            self.coins -= pay;
            CONTRACT_POT.put(key, pot - pay);
            json(ex,200,"{\"ok\":true,\"coins\":"+self.coins+",\"myPot\":"+(pot-pay)+"}");
        });

        server.createContext("/api/commerce/set-bounty", ex -> {
            String me = getSessionEmail(ex);
            if (me == null) { json(ex,401,"{\"ok\":false,\"error\":\"unauthorized\"}"); return; }
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { json(ex,405,"{\"ok\":false,\"error\":\"method\"}"); return; }

            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String,String> f = parseForm(body);
            String targetEmail = f.getOrDefault("target","").trim();
            if (targetEmail.isEmpty()) { json(ex,400,"{\"ok\":false,\"error\":\"missing_target\"}"); return; }

            User self = findUser(me);
            User target = findUser(targetEmail);
            if (self == null || target == null) { json(ex,404,"{\"ok\":false,\"error\":\"not_found\"}"); return; }
            if (self.email.equalsIgnoreCase(target.email)) { json(ex,400,"{\"ok\":false,\"error\":\"cannot_select_self\"}"); return; }

            String current = CURRENT_BOUNTY.get(me);
            if (current != null && target.email.equalsIgnoreCase(current)) {
                json(ex,400,"{\"ok\":false,\"error\":\"already_current\"}"); return; }
            if (self.coins < 1) { json(ex,400,"{\"ok\":false,\"error\":\"no_coins\"}"); return; }

            self.coins -= 1;
            CURRENT_BOUNTY.put(me, target.email.toLowerCase(Locale.ROOT));
            json(ex,200,"{\"ok\":true,\"coins\":"+self.coins+",\"assigned\":\""+escape(target.email)+"\"}");
        });

        System.out.println("Server running at http://localhost:" + port + "/");
        server.start();
    }

    // ===== Helpers =====
    private static void serveFile(HttpServer server, String path, String file) {
        server.createContext(path, ex -> {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { redirect(ex,path); return; }
            try {
                byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(file));
                ex.getResponseHeaders().add("Content-Type","text/html; charset=utf-8");
                ex.sendResponseHeaders(200, bytes.length);
                ex.getResponseBody().write(bytes);
            } catch (IOException e) {
                ex.sendResponseHeaders(404, -1);
            } finally { ex.close(); }
        });
    }
    private static Map<String,String> parseForm(String body){
        Map<String,String> m = new HashMap<>();
        if (body==null || body.isEmpty()) return m;
        for (String part : body.split("&")){
            String[] kv = part.split("=",2);
            String k = java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String v = kv.length>1 ? java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            m.put(k,v);
        }
        return m;
    }
    private static String getSessionEmail(HttpExchange ex){
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        if (cookie==null) return null;
        for (String part : cookie.split(";")){
            String[] kv = part.trim().split("=",2);
            if (kv.length==2 && kv[0].equals("session")) return kv[1].toLowerCase(Locale.ROOT);
        }
        return null;
    }
    private static void setSession(HttpExchange ex,String email){
        ex.getResponseHeaders().add("Set-Cookie","session="+email.toLowerCase(Locale.ROOT)+"; Path=/; HttpOnly");
    }
    private static void clearSession(HttpExchange ex){
        ex.getResponseHeaders().add("Set-Cookie", "session=; Max-Age=0; Path=/; HttpOnly");
    }
    private static void redirect(HttpExchange ex,String loc)throws IOException{
        ex.getResponseHeaders().add("Location", loc);
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }
    private static void json(HttpExchange ex,int code,String body)throws IOException{
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type","application/json; charset=utf-8");
        ex.sendResponseHeaders(code,b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }
    private static String escape(String s){ return s==null?"":s.replace("\\","\\\\").replace("\"","\\\""); }
    private static User findUser(String email){
        if (email==null) return null;
        synchronized (USERS){ for (User u:USERS) if (u.email.equalsIgnoreCase(email)) return u; }
        return null;
    }
}
