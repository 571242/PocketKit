package cn.xianhua.pocketkit.remote;

import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

public final class SftpHostKeysTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        byte[] first = key((byte) 3), other = key((byte) 4);
        SftpHostKeys unknown = new SftpHostKeys("nas.example", 22, "");
        check(unknown.check("nas.example", first) == HostKeyRepository.NOT_INCLUDED, "unknown blocked");
        SftpHostKeys.Rejected request = unknown.rejection();
        check(request != null && !request.changed, "first-use confirmation");
        check(request.algorithm.equals("ssh-ed25519"), "algorithm identified");
        check(request.fingerprint.startsWith("SHA256:") && request.fingerprint.length() == 50, "SHA256 display");
        check(Arrays.equals(first, Base64.getDecoder().decode(request.encodedKey)), "pin is exact key");
        unknown.add(new HostKey("nas.example", first), null);
        check(unknown.check("nas.example", first) == HostKeyRepository.NOT_INCLUDED, "SSH cannot auto-trust");
        SftpHostKeys trusted = new SftpHostKeys("nas.example", 22, request.encodedKey);
        check(trusted.check("nas.example", first) == HostKeyRepository.OK, "trusted accepted");
        check(trusted.rejection() == null, "no prompt for unchanged host");
        check(trusted.getHostKey("nas.example", "ssh-ed25519").length == 1, "known algorithm advertised");
        check(trusted.getHostKey("other.example", null).length == 0, "host isolation");
        check(trusted.check("nas.example", other) == HostKeyRepository.CHANGED, "changed key blocked");
        check(trusted.rejection().changed, "change not first use");
        check(trusted.rejection().previousFingerprint.equals(request.fingerprint), "old fingerprint preserved");
        trusted.remove("nas.example", "ssh-ed25519");
        check(trusted.check("nas.example", first) == HostKeyRepository.OK, "SSH cannot remove pin");
        SftpHostKeys nonstandard = new SftpHostKeys("nas.example", 2222, request.encodedKey);
        check(nonstandard.check("[nas.example]:2222", first) == HostKeyRepository.OK, "non-default port");
        check(nonstandard.check("nas.example", first) == HostKeyRepository.CHANGED, "port cannot alias");
        try { new SftpHostKeys("nas.example", 22, "broken!"); throw new AssertionError("bad persisted key accepted"); }
        catch (IOException expected) { checks++; }
        RemoteSession a = new RemoteSession(); a.host = "NAS.example"; a.port = 22; a.hostKey = request.encodedKey;
        RemoteSession b = a.copy(); b.username = "another-user"; b.name = "New title";
        check(a.sameServer(b) && b.hostKey.equals(a.hostKey), "rename and username preserve trust");
        b.port = 2222; check(!a.sameServer(b), "port edit resets trust");
        b = a.copy(); b.host = "another.example"; check(!a.sameServer(b), "host edit resets trust");
        b = a.copy(); b.port = 0; check(a.sameServer(b), "default port normalised");
        System.out.println("SftpHostKeysTest: " + checks + " checks passed");
    }
    private static byte[] key(byte fill) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        byte[] algorithm = "ssh-ed25519".getBytes(StandardCharsets.US_ASCII);
        out.writeInt(algorithm.length); out.write(algorithm); out.writeInt(32);
        byte[] raw = new byte[32]; Arrays.fill(raw, fill); out.write(raw); return bytes.toByteArray();
    }
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
}
