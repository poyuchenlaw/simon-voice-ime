package com.simon.voiceime;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import org.json.*;

/** Size/path metadata only; never follows symbolic links or reads file contents. */
final class StorageDiagnostics {
    static final long DAY_MS=86400000L;
    static JSONObject scan(Map<String,File> roots)throws IOException,JSONException {
        JSONObject categories=new JSONObject();Map<String,Long> unique=new HashMap<>();
        PriorityQueue<JSONObject> largest=new PriorityQueue<>(Comparator.comparingLong(o->o.optLong("bytes")));
        long[] total={0};
        for(Map.Entry<String,File> entry:roots.entrySet())
            categories.put(entry.getKey(),measure(entry.getValue(),entry.getKey(),unique,largest,total));
        List<JSONObject> ordered=new ArrayList<>(largest);ordered.sort(Comparator.comparingLong((JSONObject o)->o.optLong("bytes")).reversed());
        return new JSONObject().put("categories",categories).put("largest_files",new JSONArray(ordered)).put("total_bytes",total[0]).put("over_500_mb",total[0]>500000000L);
    }
    private static long measure(File file,String path,Map<String,Long> unique,PriorityQueue<JSONObject> largest,long[] total)throws IOException,JSONException {
        if(file==null||Files.isSymbolicLink(file.toPath())||!file.exists())return 0;
        if(file.isDirectory()) {
            File[] children=file.listFiles();if(children==null)throw new IOException("storage directory unavailable");
            long size=0;for(File child:children)size+=measure(child,path+"/"+child.getName(),unique,largest,total);return size;
        }
        if(!file.isFile())return 0;
        long size=file.length();String canonical=file.getCanonicalPath();
        if(!unique.containsKey(canonical)) {
            unique.put(canonical,size);total[0]+=size;
            largest.add(new JSONObject().put("path",path).put("bytes",size));if(largest.size()>20)largest.remove();
        }
        return size;
    }
    static boolean cleanable(String category){return "cache".equals(category)||"files".equals(category);}
    static long clear(Map<String,File> roots,String category)throws IOException {
        File root=roots.get(category);if(!cleanable(category)||root==null||!root.isDirectory())return 0;
        if(Files.isSymbolicLink(root.toPath()))throw new IOException("storage root is symbolic link");
        long removed=0;
        if("cache".equals(category)) {
            // shortcut: only known update artifacts idle for 24h, add writer coordination before widening cleanup.
            for(String name:new String[]{"update.apk","update-patched.apk","update.patch","patch-uncompress.tmp"}) {
                File file=new File(root,name);
                if(file.exists()&&!Files.isSymbolicLink(file.toPath())&&file.isFile()&&System.currentTimeMillis()-file.lastModified()>=DAY_MS)removed+=delete(file);
            }
        }else {removed+=clearObsoleteModel(root);for(String name:new String[]{"sherpa-onnx-models","sherpa-onnx-sensevoice","sherpa-onnx-streaming","silero_vad.onnx"}) {
            File file=new File(root,name);if(file.exists()&&!Files.isSymbolicLink(file.toPath()))removed+=delete(file);
        }
        }
        return removed;
    }
    static long clearObsoleteModel(File files)throws IOException {
        if(Files.isSymbolicLink(files.toPath()))throw new IOException("storage root is symbolic link");
        File models=new File(files,"models");
        if(Files.isSymbolicLink(models.toPath()))throw new IOException("models is symbolic link");
        File llm=new File(models,"llm");
        return Files.exists(llm.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)?delete(llm):0;
    }
    private static long delete(File file)throws IOException {
        if(Files.isSymbolicLink(file.toPath())){Files.delete(file.toPath());return 0;}
        long removed=0;
        if(file.isDirectory()){File[] children=file.listFiles();if(children==null)throw new IOException("cleanup directory unavailable");for(File child:children)removed+=delete(child);}else removed=file.length();
        if(!file.delete())throw new IOException("cleanup incomplete");return removed;
    }
}
