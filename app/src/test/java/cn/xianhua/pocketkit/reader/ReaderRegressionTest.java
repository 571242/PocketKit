package cn.xianhua.pocketkit.reader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import javax.xml.parsers.*;

public final class ReaderRegressionTest {
    private static int checks;
    private interface Work { void run() throws Exception; }
    private static void reject(Work work) throws Exception {
        try { work.run(); throw new AssertionError("expected rejection"); }
        catch (IOException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        if (!ReaderRegressionTest.class.desiredAssertionStatus()) throw new IllegalStateException("-ea required");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("word/")); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml")); zip.write("<a/>".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        assert ReaderIo.unzip(new ByteArrayInputStream(bytes.toByteArray())).containsKey("word/document.xml"); checks++;
        bytes.reset();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (int i = 0; i <= ReaderIo.MAX_ARCHIVE_ENTRIES; i++) { zip.putNextEntry(new ZipEntry("d" + i + "/")); zip.closeEntry(); }
        }
        reject(() -> ReaderIo.unzip(new ByteArrayInputStream(bytes.toByteArray())));
        bytes.reset();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) { zip.putNextEntry(new ZipEntry("dir/")); zip.write(1); zip.closeEntry(); }
        reject(() -> ReaderIo.unzip(new ByteArrayInputStream(bytes.toByteArray())));
        byte[] utf16 = "\uFEFF中文正文".getBytes(StandardCharsets.UTF_16LE);
        assert DocumentFormat.sniff("中文.txt", utf16) == DocumentFormat.Kind.TEXT; checks++;
        assert ReaderLoader.load("中文.txt", utf16).sections().get(0).plainText().contains("中文正文"); checks++;
        reject(() -> SafeXml.parse("<!DOCTYPE a [<!ENTITY x SYSTEM 'file:///private'>]><a>&x;</a>".getBytes(StandardCharsets.UTF_8)));
        reject(() -> SafeXml.parse("\uFEFF<!DOCTYPE a><a/>".getBytes(StandardCharsets.UTF_16LE)));
        reject(() -> SafeXml.parse(("<a>".repeat(129) + "</a>".repeat(129)).getBytes(StandardCharsets.UTF_8)));
        String old = System.getProperty("javax.xml.parsers.DocumentBuilderFactory");
        try {
            System.setProperty("javax.xml.parsers.DocumentBuilderFactory", LimitedFactory.class.getName());
            assert SafeXml.parse("<a>兼容解析</a>".getBytes(StandardCharsets.UTF_8)).getDocumentElement().getTextContent().equals("兼容解析"); checks++;
            reject(() -> SafeXml.parse("<!DOCTYPE a><a/>".getBytes(StandardCharsets.UTF_8)));
        } finally {
            if (old == null) System.clearProperty("javax.xml.parsers.DocumentBuilderFactory"); else System.setProperty("javax.xml.parsers.DocumentBuilderFactory", old);
        }
        System.out.println("ReaderRegressionTest: " + checks + " checks passed");
    }
    public static final class LimitedFactory extends DocumentBuilderFactory {
        @Override public DocumentBuilder newDocumentBuilder() throws ParserConfigurationException {
            DocumentBuilderFactory delegate = DocumentBuilderFactory.newDefaultInstance();
            delegate.setNamespaceAware(isNamespaceAware()); delegate.setExpandEntityReferences(isExpandEntityReferences());
            return delegate.newDocumentBuilder();
        }
        @Override public void setAttribute(String name, Object value) { throw new IllegalArgumentException("unsupported"); }
        @Override public Object getAttribute(String name) { throw new IllegalArgumentException("unsupported"); }
        @Override public void setFeature(String name, boolean value) throws ParserConfigurationException { throw new ParserConfigurationException("unsupported"); }
        @Override public boolean getFeature(String name) throws ParserConfigurationException { throw new ParserConfigurationException("unsupported"); }
        @Override public void setXIncludeAware(boolean value) { throw new UnsupportedOperationException(); }
    }
}
