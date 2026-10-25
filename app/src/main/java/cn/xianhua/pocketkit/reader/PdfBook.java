package cn.xianhua.pocketkit.reader;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF text, one section per page.
 *
 * <p>Pages come straight from the document, so the reader can jump by page and search across all of
 * them. A page without a text layer (a scan) is reported in place of its content rather than shown
 * blank; this reader does no OCR.
 */
public final class PdfBook {
    public static final int MAX_PAGES = 2000;

    private PdfBook() { }

    public static ReaderDocument parse(String fallbackTitle, byte[] bytes, String titleHint) throws IOException {
        try (PDDocument pdf = PDDocument.load(bytes)) {
            if (pdf.isEncrypted()) {
                throw new IOException("这份 PDF 不能直接读取文本（可能加密或只允许部分权限）。"
                        + "本阅读器不解密文档，请先用有权限的工具另存一份。");
            }
            int pages = pdf.getNumberOfPages();
            if (pages <= 0) throw new IOException("这份 PDF 没有页面。");
            if (pages > MAX_PAGES) {
                throw new IOException("这份 PDF 有 " + pages + " 页，超过本阅读器一次处理的上限 "
                        + MAX_PAGES + " 页。请先拆分再打开。");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setLineSeparator("\n");
            List<ReaderDocument.Section> sections = new ArrayList<>(pages);
            int budget = ReaderIo.MAX_TEXT_BYTES;
            int withText = 0;
            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text;
                try {
                    text = stripper.getText(pdf);
                } catch (RuntimeException damaged) {
                    text = "";
                }
                String clean = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').trim();
                String html;
                if (clean.isEmpty()) {
                    html = "<p>这一页没有可提取的文字（可能是扫描图或纯图片页）。本阅读器不做 OCR，也不把页面渲染成图片。</p>";
                } else {
                    withText++;
                    html = "<pre>" + HtmlPolicy.escape(clean) + "</pre>";
                }
                budget -= html.length();
                if (budget < 0) throw new IOException("这份 PDF 的文字超过阅读上限，请先拆分再打开。");
                sections.add(new ReaderDocument.Section("第 " + page + " 页", html, HtmlPolicy.plain(html)));
            }
            String title = titleHint == null || titleHint.isBlank() ? fallbackTitle : titleHint;
            String limitation = "PDF：" + pages + " 页，逐页显示可提取的文字。扫描页没有文字层时不做 OCR。"
                    + (withText == 0 ? "这份文件没有文字层，可能是扫描件。" : "");
            return new ReaderDocument(ReaderDocument.Format.PDF, title, sections, limitation);
        } catch (com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException encrypted) {
            throw new IOException("这份 PDF 需要密码，本阅读器不处理受保护文档。");
        } catch (IOException already) {
            throw already;
        } catch (RuntimeException damaged) {
            throw new IOException("这份 PDF 结构损坏，无法读取：" + damaged.getClass().getSimpleName(), damaged);
        }
    }
}
