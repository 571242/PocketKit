package cn.xianhua.pocketkit.media;

import java.util.Locale;

/**
 * Wi-Fi QR payloads, following the format the ZXing project documented for
 * {@code WIFI:} codes (also what Android's own Wi-Fi QR scanner reads).
 *
 * <pre>
 * WIFI:T:WPA;S:mynetwork;P:mypass;H:true;;
 * </pre>
 *
 * <p>{@code \ ; , : "} inside a value must be backslash-escaped, otherwise a network whose
 * password contains a semicolon produces a code that scans as garbage. That escaping is the whole
 * reason this class exists rather than a string concat at the call site.
 */
public final class WifiQr {

    /** Authentication the code asks the scanner to use. */
    public enum Auth {
        WPA, WEP, NOPASS
    }

    private WifiQr() {
    }

    public static String escape(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    /** Un-escapes a value read back out of a scanned payload. */
    public static String unescape(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        boolean escaped = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (escaped) {
                out.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else {
                out.append(c);
            }
        }
        if (escaped) {
            out.append('\\');
        }
        return out.toString();
    }

    /**
     * Builds the payload.
     *
     * @throws IllegalArgumentException when the SSID is empty, or a password is required but
     *                                  missing. Both cases produce a code the phone silently
     *                                  refuses to join, so failing here is kinder.
     */
    public static String payload(String ssid, String password, Auth auth, boolean hidden) {
        String name = ssid == null ? "" : ssid;
        if (name.isEmpty()) {
            throw new IllegalArgumentException("网络名称（SSID）不能为空");
        }
        Auth mode = auth == null ? Auth.WPA : auth;
        String pass = password == null ? "" : password;
        if (mode != Auth.NOPASS && pass.isEmpty()) {
            throw new IllegalArgumentException("该加密方式必须填写密码；开放网络请选择「无密码」");
        }
        StringBuilder sb = new StringBuilder("WIFI:");
        sb.append("T:").append(mode).append(';');
        sb.append("S:").append(escape(name)).append(';');
        if (mode != Auth.NOPASS) {
            sb.append("P:").append(escape(pass)).append(';');
        }
        if (hidden) {
            sb.append("H:true;");
        }
        // Terminating double semicolon is part of the format.
        sb.append(';');
        return sb.toString();
    }

    /** A parsed Wi-Fi code, or null fields when the text is not one. */
    public static final class Parsed {
        public String ssid = "";
        public String password = "";
        public Auth auth = Auth.WPA;
        public boolean hidden;

        public boolean isWifi() {
            return !ssid.isEmpty();
        }

        @Override public String toString() {
            return "SSID：" + ssid + "\n加密：" + label(auth) + (hidden ? "（隐藏）" : "")
                    + (auth == Auth.NOPASS ? "" : "\n密码：" + password);
        }
    }

    public static String label(Auth auth) {
        switch (auth == null ? Auth.WPA : auth) {
            case NOPASS: return "无密码";
            case WEP: return "WEP";
            default: return "WPA/WPA2";
        }
    }

    /** Reads a scanned {@code WIFI:} payload. Returns an empty result for anything else. */
    public static Parsed parse(String text) {
        Parsed out = new Parsed();
        if (text == null) {
            return out;
        }
        String body = text.trim();
        if (body.length() < 5 || !body.regionMatches(true, 0, "WIFI:", 0, 5)) {
            return out;
        }
        body = body.substring(5);
        // Split on unescaped semicolons.
        StringBuilder field = new StringBuilder();
        java.util.List<String> fields = new java.util.ArrayList<>();
        boolean escaped = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (escaped) {
                field.append('\\').append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == ';') {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0) {
            fields.add(field.toString());
        }
        for (String f : fields) {
            if (f.length() < 2) {
                continue;
            }
            char key = Character.toUpperCase(f.charAt(0));
            String value = f.substring(1);
            if (value.startsWith(":")) {
                value = value.substring(1);
            } else {
                continue;
            }
            switch (key) {
                case 'S': out.ssid = unescape(value); break;
                case 'P': out.password = unescape(value); break;
                case 'H': out.hidden = value.equalsIgnoreCase("true"); break;
                case 'T':
                    String t = value.toUpperCase(Locale.US);
                    if (t.equals("NOPASS")) {
                        out.auth = Auth.NOPASS;
                    } else if (t.equals("WEP")) {
                        out.auth = Auth.WEP;
                    } else {
                        out.auth = Auth.WPA;
                    }
                    break;
                default: break;
            }
        }
        return out;
    }
}
