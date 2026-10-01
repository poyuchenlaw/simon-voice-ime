package com.simon.voiceime;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Bridges installed/read-back vocabulary to Rime. No inferred readings or term logging. */
final class RimeVocabularyInstaller {
    private static volatile boolean learningEnabled = true;
    private static long tableRevision;
    static void setLearningEnabled(boolean enabled) { learningEnabled = enabled; }
    static synchronized long revision() { return tableRevision; }
    private static final String SYMBOLS = "ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦˉˊˇˋ˙";
    private static final String KEYS = "1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/- 6347";

    static synchronized void install(File user, List<String[]> entries) throws IOException {
        user.mkdirs();
        StringBuilder source = new StringBuilder();
        for (String[] entry : entries) {
            if (entry != null && entry.length >= 2 && safe(entry[0]) && safe(entry[1]))
                source.append(entry[0]).append('\t').append(entry[1]).append('\n');
        }
        writeChanged(new File(user, "installed_vocab.tsv"), source.toString());
        rebuild(user);
    }

    static synchronized void remember(File user, String word, String reading) throws IOException {
        if (!learningEnabled) return;
        List<String> syllables = syllables(reading);
        // Only a one-character/one-syllable alignment can lift individual characters.
        if (!safe(word) || word.codePointCount(0, word.length()) != syllables.size() || syllables.isEmpty()) return;
        LinkedHashMap<String,String> learned = load(new File(user, "committed_vocab.tsv"));
        learned.put(word + "\t" + reading, word + "\t" + reading);
        // Bound the on-device explicit-commit history, retaining recent entries.
        while (learned.size() > 2000) learned.remove(learned.keySet().iterator().next());
        writeChanged(new File(user, "committed_vocab.tsv"), String.join("\n", learned.values()) + "\n");
        rebuild(user);
    }

    static boolean isPhoneticReading(String value) {
        return value != null && !value.isEmpty() && value.codePoints().allMatch(cp -> SYMBOLS.indexOf(cp)>=0 || cp==' ');
    }
    private static boolean safe(String value) {
        return value != null && !value.isEmpty() && value.indexOf('\n') < 0 && value.indexOf('\r') < 0 && value.indexOf('\t') < 0;
    }
    private static LinkedHashMap<String,String> load(File path) throws IOException {
        LinkedHashMap<String,String> entries = new LinkedHashMap<>();
        if (path.isFile()) for (String line : Files.readAllLines(path.toPath(), StandardCharsets.UTF_8)) {
            if (line.split("\t", -1).length == 2) entries.put(line, line);
        }
        return entries;
    }
    private static List<String> syllables(String reading) {
        List<String> result = new ArrayList<>();
        if (reading == null) return result;
        String separated=reading.replaceAll("([1-5ˉˊˇˋ˙])", "$1 ");
        for (String part : separated.trim().split("\\s+")) {
            StringBuilder code = new StringBuilder();
            for (int i=0;i<part.length();i++) {
                char ch=part.charAt(i); int index=SYMBOLS.indexOf(ch);
                if(index>=0) code.append(KEYS.charAt(index));
                else if(ch>='1'&&ch<='5') code.append(" 6347".charAt(ch-'1'));
                else return Collections.emptyList();
            }
            if(code.length()==0) return Collections.emptyList();
            result.add(code.toString());
        }
        return result;
    }
    private static String toneless(String code) {
        return code.replaceAll("[ 6347]", "");
    }
    private static void add(Map<String,Integer> table, String word, String code, int weight) {
        code=code.replaceAll(" +$", "");
        if (code.isEmpty()) return;
        String row=word+"\t"+code;
        table.merge(row,weight,Math::max);
    }
    private static void phrase(Map<String,Integer> table,String word,List<String> codes,int weight) {
        // Linear-size aliases: exact/toneless readings and full-prefix + remaining initials.
        for(int split=0;split<=codes.size();split++) for(boolean noTones:new boolean[]{false,true}) {
            StringBuilder code=new StringBuilder();
            for(int i=0;i<codes.size();i++) {
                String syllable=codes.get(i);
                code.append(i<split ? (noTones?toneless(syllable):syllable) : toneless(syllable).substring(0,1));
            }
            add(table,word,code.toString(),weight);
        }
    }
    private static void rebuild(File user) throws IOException {
        Map<String,Integer> table = new TreeMap<>();
        for(String filename:new String[]{"installed_vocab.tsv","committed_vocab.tsv"}) {
            boolean committed=filename.startsWith("committed");
            for(String line:load(new File(user,filename)).values()) {
                String[] fields=line.split("\t",2); String word=fields[0];
                List<String> codes=syllables(fields[1]);
                if(codes.isEmpty())continue;
                boolean han=word.codePoints().allMatch(cp->Character.UnicodeScript.of(cp)==Character.UnicodeScript.HAN);
                if(!han)continue;
                phrase(table,word,codes,committed?100000:10000);
                if(codes.size()!=word.codePointCount(0,word.length()))continue;
                int offset=0;
                for(String code:codes) {
                    int cp=word.codePointAt(offset);offset+=Character.charCount(cp);
                    String character=new String(Character.toChars(cp));
                    add(table,character,code,committed?1000:100);
                    add(table,character,toneless(code),committed?1000:100);
                }
            }
        }
        StringBuilder text=new StringBuilder("# Generated from installed vocabulary and explicit commits.\n");
        for(Map.Entry<String,Integer> row:table.entrySet())text.append(row.getKey()).append('\t').append(row.getValue()).append('\n');
        writeChanged(new File(user,"custom_phrase.txt"),text.toString());
    }
    private static void writeChanged(File target,String text) throws IOException {
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
        if(target.isFile()&&Arrays.equals(bytes,Files.readAllBytes(target.toPath())))return;
        File temp=new File(target.getParentFile(),target.getName()+".tmp");
        Files.write(temp.toPath(),bytes);
        Files.move(temp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        if (target.getName().equals("custom_phrase.txt")) tableRevision++;
    }
}
