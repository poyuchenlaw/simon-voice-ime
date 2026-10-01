package com.simon.voiceime;
import java.nio.file.*;
import java.util.*;
public final class VocabularyHostTest {
    public static void main(String[] args) throws Exception {
        Path files=Paths.get(args[1]); Files.createDirectories(files.resolve("rime/user"));
        Files.writeString(files.resolve("zhuyin_remote_private.tsv"),"ㄅㄇ\t泊旻\tㄅㄛ2 ㄇㄧㄣ2\nㄆㄇ\t珀旻\tㄆㄛ4 ㄇㄧㄣ2\n");
        RimeVocabularyInstaller.install(files.resolve("rime/user").toFile(),Arrays.asList(
            new String[]{"泊旻","ㄅㄛ2 ㄇㄧㄣ2"},new String[]{"珀旻","ㄆㄛ4 ㄇㄧㄣ2"}));
        try(RimeZhuyinNative engine=new RimeZhuyinNative(args[0],files.resolve("rime/user").toString())) {
            replay(engine,"1i6aup6","泊旻",1);
            replay(engine,"1i6a","泊旻",1);
            replay(engine,"1a","泊旻",5);
            replay(engine,"qi4","珀",5);
            replay(engine,"1i6","膊",200);
            replay(engine,"1i6","膊",1);
            RimeVocabularyInstaller.install(files.resolve("rime/user").toFile(),Arrays.asList(
                new String[]{"波旻","ㄅㄛ1 ㄇㄧㄣ2"},new String[]{"璧旻","ㄅㄧ4 ㄇㄧㄣ2"},new String[]{"巒峯","ㄌㄨㄢ2 ㄈㄥ1"}));
            replay(engine,"1i aup6","波旻",1);
            replay(engine,"1u4aup6","璧旻",1);
            replay(engine,"jo4","為",10);
            replay(engine,"xj06z/ ","巒峯",1);
            Path history=files.resolve("rime/user/committed_vocab.tsv");
            byte[] before=Files.readAllBytes(history);
            RimeVocabularyInstaller.setLearningEnabled(false);
            replay(engine,"1i6","薄",200);
            if(!Arrays.equals(before,Files.readAllBytes(history)))throw new AssertionError("protected-field vocabulary persisted");
            RimeVocabularyInstaller.setLearningEnabled(true);
            engine.clear();engine.key('1');engine.key('i');engine.key('6');
            String[] activeMenu=engine.candidates();
            try(RimeZhuyinNative other=new RimeZhuyinNative(args[0],files.resolve("rime/user").toString())) {
                RimeVocabularyInstaller.install(files.resolve("rime/user").toFile(),Collections.singletonList(
                    new String[]{"珀琮","ㄆㄛ4 ㄘㄨㄥ2"}));
                if(!Arrays.equals(activeMenu,engine.candidates()))throw new AssertionError("refresh changed an in-flight menu");
                engine.clear();engine.key('q');engine.candidates(); // Two live sessions defer reload safely.
            }
            replay(engine,"qi4hj/6","珀琮",1);
        }
        System.out.println("PASS installed exact, mixed, initials and derived character");
    }
    static void replay(RimeZhuyinNative engine,String keys,String expected,int top) {
        engine.clear(); for(char key:keys.toCharArray()){engine.key(key); engine.composing(); engine.candidates();}
        List<String> actual=Arrays.asList(engine.candidates());
        if(new HashSet<>(actual).size()!=actual.size())throw new AssertionError("duplicate candidates");
        int rank=actual.indexOf(expected)+1;
        if(rank<1||rank>top)throw new AssertionError("keys="+keys+" expected="+expected+" top="+top+" actual="+actual.subList(0,Math.min(10,actual.size())));
        engine.choose(rank-1); engine.composing(); engine.candidates(); engine.enter();
        String committed=engine.takeCommit(); if(!expected.equals(committed))throw new AssertionError("selection committed="+committed+" expected="+expected);
    }
}
