// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame;
import java.io.*;
import java.nio.file.*;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/** Run with the dated mirror directory containing the four Tokyo/London pack inputs and the real saves. */
public class CityPackStoreTest {
    public static void main(String[] args)throws Exception {
        File root=Files.createTempDirectory("world-native-pack-test").toFile();
        Path data=Paths.get(args[0]), catalog=Paths.get("android-lib/src/main/assets/world-native/cities.tsv");
        AtomicInteger transfers=new AtomicInteger();
        CityPackStore store=new CityPackStore(root,Files.newBufferedReader(catalog),p->{transfers.incrementAndGet();return Files.newInputStream(data.resolve(p));});
        if(store.cities().size()!=38)throw new AssertionError();
        CityPackStore.CityPack tokyo=store.load("tyo"), london=store.load("lon");
        if(!tokyo.city.name.equals("Tokyo")||!london.city.name.equals("London")||tokyo.network.equals(london.network)||transfers.get()!=10)throw new AssertionError();
        CityPackStore offline=new CityPackStore(root,Files.newBufferedReader(catalog),p->{throw new IOException("offline");});
        if(!Arrays.equals(Files.readAllBytes(tokyo.network.toPath()),Files.readAllBytes(offline.load("tyo").network.toPath())))throw new AssertionError();
        if(!offline.load("lon").network.isFile())throw new AssertionError();
        File corrupt=london.market;
        try(RandomAccessFile f=new RandomAccessFile(corrupt,"rw")){int first=f.read();f.seek(0);f.write(first^0x40);}
        try{offline.load("lon");throw new AssertionError("corruption accepted");}catch(IOException expected){}
        if(!tokyo.network.exists()||!london.network.exists())throw new AssertionError("previous cache lost");
        CityPackStore wrong=new CityPackStore(root,Files.newBufferedReader(catalog),p->new ByteArrayInputStream(new byte[1]));
        try{wrong.load("lon");throw new AssertionError("wrong pack accepted");}catch(IOException expected){}
        if(!store.load("lon").network.isFile()||transfers.get()!=15)throw new AssertionError();
        if(store.load("jak").network!=null||!offline.load("jak").terrain.isFile()||transfers.get()!=19)throw new AssertionError("Jakarta must open offline without a fictional network");
        System.out.println("37 real cities plus Jakarta sandbox, Tokyo and London verified, restart offline, failed downloads preserve both worlds, atomic repair passed");
    }
}
