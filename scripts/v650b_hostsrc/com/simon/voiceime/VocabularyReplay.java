package com.simon.voiceime;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** Generic local replay. Private payload/output paths are supplied by the caller. */
public final class VocabularyReplay {
    public static void main(String[] args) throws Exception {
        Path user=Paths.get(args[1]);Files.createDirectories(user);
        List<String[]> entries=new ArrayList<>();
        for(String row:Files.readAllLines(Paths.get(args[2]),StandardCharsets.UTF_8))entries.add(row.split("\t",2));
        if(args[3].equals("install"))RimeVocabularyInstaller.install(user.toFile(),entries);
        try(RimeZhuyinNative engine=new RimeZhuyinNative(args[0],args[1]);BufferedReader in=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8))) {
            String line;
            while((line=in.readLine())!=null) {
                String[] row=line.split("\t",-1);if(row.length!=2)throw new IllegalArgumentException("id/key row");
                engine.clear();List<Long> durations=new ArrayList<>();
                for(char key:row[1].toCharArray()) {
                    long start=System.nanoTime();engine.key(key);engine.composing();engine.candidates();
                    durations.add(System.nanoTime()-start);
                }
                System.out.println(row[0]+"\t"+row[1]+"\t"+String.join("\u001f",engine.candidates())+"\t"+durations.toString());
            }
        }
    }
}
