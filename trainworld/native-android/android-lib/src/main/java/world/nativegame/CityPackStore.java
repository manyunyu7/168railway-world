// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame;

import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;

/** Release-pinned, private, offline-capable cache. The old slot is never written while downloading. */
public final class CityPackStore {
    public static final String RELEASE = "2026-11-18";
    public static final String ORIGIN = "https://168railway.com/studio-data/" + RELEASE + "/";
    private static final java.util.regex.Pattern CITY_ID = java.util.regex.Pattern.compile("[a-z0-9]{2,12}");
    private static final java.util.regex.Pattern DIGEST = java.util.regex.Pattern.compile("[a-f0-9]{64}");
    private static final Object LOCK = new Object();
    public interface Progress { void downloaded(long bytes, long total); }
    interface Transport { InputStream open(String path) throws IOException; }
    public static final class City {
        public final String id, name;
        public final long bytes;
        public final boolean hasNetwork;
        private final List<Asset> assets;
        private City(String id, String name, List<Asset> assets) {
            this.id=id; this.name=name; this.assets=assets; this.hasNetwork=assets.stream().anyMatch(a -> a.local.equals("network.save"));
            long count=0; for (Asset part: assets) count+=part.size; bytes=count;
        }
    }
    public static final class CityPack {
        public final File header, cells, market, terrain, network, places;
        public final City city;
        private CityPack(File dir, City city) {
            this.city=city;
            header=new File(dir,"header.json"); cells=new File(dir,"cells.bin");
            market=new File(dir,"market.json"); terrain=new File(dir,"zone.terrain");
            places=new File(dir,"places.json");
            network=city.hasNetwork ? new File(dir,"network.save") : null;
        }
    }
    private static final class Asset {
        final String remote, local, sha; final long size;
        Asset(String remote,String local,String sha,long size){this.remote=remote;this.local=local;this.sha=sha;this.size=size;}
    }
    private final File root;
    private final Map<String,City> cities = new LinkedHashMap<>();
    private final Transport transport;
    public CityPackStore(File privateFiles, Reader catalog) throws IOException {
        this(privateFiles,catalog,CityPackStore::fetch);
    }
    CityPackStore(File privateFiles, Reader catalog, Transport transport) throws IOException {
        this.root=new File(privateFiles,"world-data/"+RELEASE);
        this.transport=transport;
        try (BufferedReader rows=new BufferedReader(catalog)) {
            String line;
            while ((line=rows.readLine())!=null) {
                if(line.isEmpty()||line.startsWith("#"))continue;
                String[] v=line.split("\t",-1);
                if((v.length!=12 && v.length!=14) || !CITY_ID.matcher(v[0]).matches() || v[1].isEmpty() || cities.containsKey(v[0]))throw new IOException("Invalid city catalog");
                String id=v[0];
                String[] rem={id+".json",id+".bin",id+".market.json","terrain/"+id+".terrain","real/"+id+".save",id+".places.json"};
                String[] loc={"header.json","cells.bin","market.json","zone.terrain","network.save","places.json"};
                List<Asset> parts=new ArrayList<>();
                for(int i=0;i<(v.length-2)/2;i++){
                    long size=Long.parseLong(v[2+2*i]);String sha=v[3+2*i];
                    if(i==4 && size==0 && sha.equals("-")) continue;
                    if(size<=0||size>256L*1024*1024||!DIGEST.matcher(sha).matches())throw new IOException("Invalid asset catalog");
                    parts.add(new Asset(rem[i],loc[i],sha,size));
                }
                cities.put(id,new City(id,v[1],parts));
            }
        }
    }
    public Collection<City> cities(){return Collections.unmodifiableCollection(cities.values());}
    public City city(String id){City c=cities.get(id);if(c==null)throw new IllegalArgumentException("Unknown city");return c;}
    public boolean cached(String id){City c=city(id); return new File(root,c.id+(c.hasNetwork?"/network.save":"/zone.terrain")).isFile();}
    public CityPack load(String id) throws IOException {return load(id,(n,t)->{});}
    public CityPack load(String id, Progress progress) throws IOException {
        City city=city(id);
        synchronized(LOCK) {
            File dest=new File(root,id);
            if(valid(dest,city))return new CityPack(dest,city);
            if(!root.isDirectory()&&!root.mkdirs())throw new IOException("Penyimpanan tidak tersedia");
            File tmp=new File(root,id+".download-"+UUID.randomUUID());
            if(!tmp.mkdir())throw new IOException("Penyimpanan tidak tersedia");
            try {
                long done=0;
                for(Asset part:city.assets){
                    MessageDigest digest=sha();
                    long count=0;
                    File out=new File(tmp,part.local);
                    try(InputStream in=transport.open(part.remote);FileOutputStream f=new FileOutputStream(out)){
                        byte[] buffer=new byte[65536]; int n;
                        while((n=in.read(buffer))!=-1){
                            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                            count+=n;if(count>part.size)throw new IOException("Ukuran paket melebihi katalog");
                            digest.update(buffer,0,n);f.write(buffer,0,n);progress.downloaded(done+count,city.bytes);
                        }
                        f.getFD().sync();
                    }
                    if(count!=part.size||!hex(digest.digest()).equals(part.sha))throw new IOException("Verifikasi paket gagal: "+part.remote);
                    done+=count;
                }
                File old=null;
                if(dest.exists()){
                    old=new File(root,id+".replaced-"+UUID.randomUUID());
                    if(!dest.renameTo(old))throw new IOException("Paket lama sedang dipakai");
                }
                if(!tmp.renameTo(dest)){
                    if(old!=null&&!old.renameTo(dest))throw new IOException("Paket lama tersimpan di "+old.getName());
                    throw new IOException("Paket gagal disimpan");
                }
                if(old!=null)cleanPrivateCache(old);
                return new CityPack(dest,city);
            } finally { cleanPrivateCache(tmp); }
        }
    }
    private static void cleanPrivateCache(File dir){
        File[] files=dir.listFiles();if(files!=null)for(File f:files)if(f.isFile())f.delete();
        if(dir.exists())dir.delete();
    }
    private static boolean valid(File dir,City city)throws IOException{
        if(!dir.isDirectory())return false;
        byte[] buffer=new byte[65536];
        for(Asset a:city.assets){
            File f=new File(dir,a.local);if(!f.isFile()||f.length()!=a.size)return false;
            MessageDigest digest=sha();
            try(InputStream in=new FileInputStream(f)){int n;while((n=in.read(buffer))!=-1)digest.update(buffer,0,n);}
            if(!hex(digest.digest()).equals(a.sha))return false;
        }
        return true;
    }
    private static MessageDigest sha(){
        try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private static String hex(byte[] bytes){
        StringBuilder s=new StringBuilder();for(byte b:bytes){s.append(Character.forDigit((b&255)>>>4,16)).append(Character.forDigit(b&15,16));}return s.toString();
    }
    private static InputStream fetch(String path)throws IOException{
        HttpURLConnection c=(HttpURLConnection)new URL(ORIGIN+path).openConnection();
        c.setInstanceFollowRedirects(false);c.setConnectTimeout(15000);c.setReadTimeout(30000);
        c.setRequestProperty("Accept-Encoding","identity");
        try {
            if(c.getResponseCode()!=200)throw new IOException("HTTP "+c.getResponseCode()+" untuk "+path);
            return new FilterInputStream(c.getInputStream()){@Override public void close()throws IOException{try{super.close();}finally{c.disconnect();}}};
        } catch(IOException e){c.disconnect();throw e;}
    }
}
