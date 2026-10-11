package com.simon.voiceime;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

public class DualHandW7AssetsTest {
    private final Path assets=Path.of("src/phone/assets/dual_hand");
    private String hash(Path path)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
    @Test public void neutralAiTableAndPublicExamplesPreserveDisplay()throws Exception {
        assertEquals("AI table: independently approved source projection","5c1f6f3386a6317fffa96259feff0798566d737c0ba6ced5f7efe654c2643d49",hash(assets.resolve("draft_chars_ai.tsv")));
        assertEquals("display unchanged","437328def90605faeaae6ca57ca32cb9f0a7f5e6b000c35198ae17648f90323c",hash(assets.resolve("draft_chars.tsv")));
        DualHandTable table=new DualHandTable(Files.newBufferedReader(assets.resolve("dual_hand_map.tsv")),Files.newBufferedReader(assets.resolve("left_hand.tsv")),Files.newBufferedReader(assets.resolve("draft_chars.tsv")),Files.newBufferedReader(assets.resolve("draft_chars_ai.tsv")));
        String[][] examples={
            {"ㄑㄧㄥ ㄑㄧㄡ ㄐㄧㄢ ㄕㄠ ㄐㄧㄚ ㄐㄧㄣ","慶秋艦燒甲晉","請球間少加禁"},
            {"ㄇㄞ ㄕㄡ ㄖㄣ ㄉㄜ ㄑㄧㄥ ㄑㄧㄡ ㄐㄧㄢ ㄕㄠ ㄐㄧㄚ ㄐㄧㄣ ㄅㄧㄥ ㄐㄧㄝ ㄔㄨ ㄏㄜ ㄩㄝ","賣守忍德慶秋艦燒甲晉兵皆觸荷曰","買收人得請球間少加禁並接出和月"},
            {"ㄗㄨ ㄖㄣ ㄕ ㄒㄧㄠ ㄓ ㄓㄨㄥ ㄉㄨㄢ ㄕ ㄕ ㄇㄧㄥ ㄑㄩㄝ ㄧㄥ ㄩ ㄘㄞ ㄒㄧㄣ","祖忍屍孝殖鍾端屍屍命缺映欲裁辛","組人是小之中段是是名卻應於才心"},
            {"ㄑㄩㄝ ㄖㄣ ㄨㄟ ㄩㄝ ㄊㄧㄠ ㄎㄨㄢ","缺忍韋曰跳寬","卻人為月調款"}
        };
        for(String[] e:examples){StringBuilder ai=new StringBuilder(),display=new StringBuilder();for(String sound:e[0].split(" ")){ai.append(table.aiDraft(sound));display.append(table.draft(sound));}assertEquals(e[1],ai.toString());assertEquals(e[2],display.toString());}
    }
}
