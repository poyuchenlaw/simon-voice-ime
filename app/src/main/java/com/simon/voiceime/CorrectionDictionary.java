package com.simon.voiceime;
import android.os.Handler;import java.io.*;import java.nio.charset.StandardCharsets;import java.util.*;
/** One dictionary and isolated learning store for every correction page in a service. */
final class CorrectionDictionary {
    private final SimonIMEService service;private final Handler ui;
    private DualHandTracks tracks;private DualHandMemory memory;private boolean loading;
    private final List<CorrectionEditor> waiting=new ArrayList<>();
    CorrectionDictionary(SimonIMEService service,Handler ui){this.service=service;this.ui=ui;}
    void load(CorrectionEditor editor){
        if(tracks!=null){ui.post(()->deliver(editor));return;}
        waiting.add(editor);if(loading)return;loading=true;
        Thread loader=new Thread(()->{
            try(Reader words=asset("phrase_words.tsv");Reader grid=asset("grid_chars.tsv");Reader readings=asset("readings.tsv")){
                DualHandMemory personal=null;try{personal=new DualHandMemory(new File(service.getFilesDir(),"dual_hand_personal.tsv"));}
                catch(IOException failure){ui.post(()->service.dualHandStatus("個人記憶未載入，本機詞庫仍可使用"));}
                DualHandTracks loaded=new DualHandTracks(words,grid,readings,personal);DualHandMemory learned=personal;
                ui.post(()->{tracks=loaded;memory=learned;for(CorrectionEditor e:waiting)deliver(e);waiting.clear();});
            }catch(IOException failure){ui.post(()->{loading=false;waiting.clear();service.dualHandStatus("本機詞庫未載入，草稿已保留");});}
        },"DualHandDictionary");loader.setDaemon(true);loader.start();
    }
    private Reader asset(String name)throws IOException{return new InputStreamReader(service.getAssets().open("dual_hand/"+name),StandardCharsets.UTF_8);}
    private void deliver(CorrectionEditor editor){if(!editor.closed){editor.tracks=tracks;editor.memory=memory;editor.ready();}}
}
