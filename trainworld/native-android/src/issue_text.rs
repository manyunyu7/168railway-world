//! Engine refusals in the web's Indonesian (game/issues.ts TEXT over i18n id.json), generated.
pub(crate) fn describe(kinds: &str) -> String {
    let mut seen = Vec::new();
    for kind in kinds.split('\n').filter(|k| !k.is_empty()) {
        let text = match kind {
            "Geom:ZeroLeg" => "Ada dua titik di tempat yang sama.",
            "Geom:Reversal" => "Rel akan berbalik ke dirinya sendiri.",
            "Geom:LevelRange" => "Tingkat berkisar dari −3 sampai +3.",
            "NodeLevel" => "Tingkat berkisar dari −3 sampai +3.",
            "GroundOverWater" => "Rel di permukaan tanah tidak bisa menyeberangi air. Pilih viaduk atau terowongan.",
            "BridgeNoRoom" => "Tidak cukup ruang untuk tanjakan jembatan",
            "BridgeNoRoomStation" => "Tidak cukup ruang untuk tanjakan jembatan antara stasiun dan air",
            "BridgeNoRoomJunction" => "Tidak cukup ruang untuk tanjakan jembatan antara persimpangan dan air",
            "BridgeNoRoomBuilt" => "Tidak cukup ruang untuk tanjakan jembatan antara rel yang sudah dibangun dan air",
            "Crossing" => "Rel tidak bisa bersilangan di tanjakan kecuali selisihnya satu tingkat penuh.",
            "CrossingOverlap" => "Rel baru akan menumpuk di atas rel yang sudah ada.",
            "NodeHeading" => "Rel harus keluar dari persimpangan searah dengan rel yang sudah ada.",
            "NodeOneSided" => "Rel harus menembus stasiun.",
            "TooManyPorts" => "Terlalu banyak rel bertemu di sini.",
            "StationPorts" => "Sudah ada stasiun di sini.",
            "PlatformStation" => "Sudah ada stasiun di sini.",
            "StationJunction" => "Stasiun tidak bisa berada di persimpangan.",
            "PlatformJunction" => "Stasiun tidak bisa berada di persimpangan.",
            "StationGroundOverWater" => "Stasiun di permukaan tanah tidak bisa berdiri di air.",
            "StationInUse" => "Ada lini yang berhenti di sini. Keluarkan stasiun dari lini itu dulu.",
            "LineStops" => "Lini butuh minimal dua stasiun yang berbeda.",
            "LinePath" => "Tidak ada rel yang menghubungkan stasiun-stasiun ini dengan urutan ini.",
            "InsertStop" => "Tidak ada rel yang menghubungkan stasiun-stasiun ini kalau perhentian itu disisipkan. Cek sambungan relnya.",
            "DropStop" => "Lini harus tetap punya perhentian ini.",
            "LineSchedule" => "Kereta per jam tidak boleh negatif.",
            "StationCurveTight" => "Rel di sini menikung terlalu tajam untuk stasiun.",
            "SplitPoint" => "Terlalu dekat dengan ujung potongan rel, atau di tanjakan. Stasiun, persimpangan, dan cabang butuh rel 5 m di tiap sisi.",
            "NodeInUse" => "Rel yang sudah dibangun tidak bisa dipindah. Hapus lalu gambar lagi.",
            "NoSuchEdge" => "Rel itu sudah tidak ada.",
            "StationTrack" => "Stasiun butuh rel ini. Hapus stasiunnya dulu.",
            "NoSuchNode" => "Itu sudah tidak ada.",
            "NoSuchLine" => "Lini itu sudah tidak ada.",
            "EdgeLoop" => "Rel tidak bisa mulai dan berakhir di tempat yang sama.",
            "Tracks" => "Harus rel ganda atau rel empat.",
            "OutsideWorld" => "Rel tidak bisa masuk dalam 5 derajat dari kutub atau melintasi meridian 180.",
            "Crossover" => "Crossover tidak bisa dibentuk ulang.",
            "Alongside" => "Rel ini sudah punya pasangan di sebelahnya.",
            "HighSpeed" => "Rel kecepatan tinggi tidak bisa dijadikan rel empat.",
            "NoSingle:Crossover" => "Crossover butuh rel ganda.",
            "NoSingle:OneWay" => "Ada rel satu arah yang tersambung ke salah satu dari dua relnya.",
            "NoSingle:Flyover" => "Flyover butuh rel ganda.",
            "NoWayBack" => "Tidak ada jalan kembali dari stasiun ini di rel satu arah.",
            "OneTrackStop" => "Di sini kereta hanya berhenti untuk arah sebaliknya.",
            "OneTrackHere" => "Peron di satu rel butuh rel yang menembus stasiun.",
            "TrackFamily" => "Rel trem dan rel kereta tidak bisa disambung. Keduanya bisa bersilangan.",
            "NothingToConstruct" => "Semua di sini sudah dibangun.",
            "TerrainMissing" => "Data medan di sini gagal dimuat. Coba lagi.",
            "RoadsMissing" => "Data jalan di sini gagal dimuat. Coba lagi.",
            "Internal" => "Perubahan itu gagal dan dibatalkan.",
            "BuildingCorners" => "Gedung stasiun punya 3 sampai 24 sudut.",
            "BuildingEdgesCross" => "Garis luarnya tidak boleh memotong dirinya sendiri.",
            "BuildingOverlap" => "Gedung stasiun tidak boleh bertumpuk.",
            "BuildingNoStation" => "Gambar gedung di sekitar stasiun yang sudah dibangun.",
            "NoSuchBuilding" => "Gedung stasiun itu sudah tidak ada.",
            k if k.starts_with("Money:") => {
                let v: Vec<f64> = k.split(':').skip(1).filter_map(|x| x.parse().ok()).collect();
                if v.len() == 2 { format!("Kas tidak cukup: butuh {} juta, ada {} juta.", v[0].round(), v[1].round()) } else { "Kas tidak cukup.".into() }.leak()
            }
            _ => "Itu tidak bisa ditaruh di sini.",
        };
        if !seen.contains(&text) { seen.push(text); }
    }
    if seen.is_empty() { return "Itu tidak bisa ditaruh di sini.".into(); }
    seen.join(" ")
}
