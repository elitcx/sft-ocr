package id.dotcode.braille.ocr.app

import androidx.compose.runtime.staticCompositionLocalOf
import id.dotcode.braille.ocr.model.FailureReason
import java.util.Locale

/**
 * Every user-facing string, in Indonesian (default) and English. An interface rather than
 * `strings.xml` so the in-app ID/EN switch works instantly without recreating the activity,
 * and so a missing translation is a compile error.
 */
interface Strings {
    val locale: Locale

    // Common
    val appName: String get() = "BRaiLLE"
    val byDotCode: String
    val back: String
    val close: String
    val cancel: String
    val retry: String
    val delete: String
    val undo: String
    val navHome: String
    val navScan: String
    val navHistory: String
    val navSettings: String
    fun today(time: String): String
    fun yesterday(time: String): String
    fun words(count: Int): String

    // Guided capture
    fun instruction(instruction: Instruction): String
    val photoTaken: String

    // Setup and tutorial
    val setupTitle: String
    val setupSubtitle: String
    val setupHelpTitle: String
    val setupHelpDesc: String
    val guidedCapture: String
    val guidedCaptureDesc: String
    val spokenStatus: String
    val spokenStatusDesc: String
    val hapticsTitle: String
    val hapticsDesc: String
    val afterScanTitle: String
    val afterScanDesc: String
    fun afterScanOption(option: AppPrefs.AfterScan): String
    val setupAdvancedNote: String
    val setupContinue: String
    val openSetup: String
    val openTutorial: String
    val skip: String
    fun tutorialStep(step: Int, total: Int): String
    val tutorialOverviewTitle: String
    val tutorialOverviewBody: String
    val tutorialHoldTitle: String
    val tutorialHoldBody: String
    val tutorialReadTitle: String
    val tutorialReadBody: String
    val tutorialFeedbackTitle: String
    val tutorialFeedbackBody: String
    val tutorialSampleSentence: String
    val tutorialFinish: String
    val trySuccess: String
    val tryFailure: String
    val tryTick: String
    val hapticsUnavailable: String
    val hapticsOff: String

    // Onboarding
    val onboardTitle: String
    val onboardSubtitle: String
    val onboardButton: String

    // Home
    val deviceReady: String
    val deviceNotPaired: String
    val deviceBluetoothOff: String
    val devicePermission: String
    val deviceUnsupported: String
    val homeScanTitle: String
    val homeScanDesc: String
    val homeScanButton: String
    val homeUploadButton: String
    val statScanned: String
    val statWords: String
    val recentTitle: String
    val seeAll: String
    val recentEmptyTitle: String
    val recentEmptyDesc: String

    // Scan
    val scanHint: String
    val autoScan: String
    val recommended: String
    val gallery: String
    val document: String
    val flashOn: String
    val flashOff: String
    val takePhoto: String
    val scannerUnavailable: String
    val captureFailed: String
    val cameraPermTitle: String
    val cameraPermDesc: String
    val cameraPermGrant: String
    val openAppSettings: String

    // Process
    val procTitle: String
    val procDone: String
    val procHint: String
    val stepPrepareImage: String
    val stepOcr: String
    val stepGemini: String
    val stepOpenDocument: String
    val stepExtract: String
    val stepBraille: String
    val almostDone: String
    fun timeLeft(seconds: Int): String

    // Result
    val resultTitle: String
    val tagReady: String
    val tagFailed: String
    val originalText: String
    val beforeCorrection: String
    val afterCorrection: String
    val showAll: String
    val showLess: String
    val brailleOutput: String
    fun moreWordsInReadMode(count: Int): String
    val sendToPad: String
    val sending: String
    fun sent(bytes: Int): String
    val sendFailed: String
    val openConnection: String
    val readMode: String
    val share: String
    val exportText: String
    val exportTextDesc: String
    val exportJson: String
    val exportJsonDesc: String
    fun offlineFixes(count: Int): String
    val geminiNoChange: String
    fun geminiChanged(blocks: Int): String
    val geminiFailed: String
    val moreTools: String
    val detailView: String
    val detailViewDesc: String
    val markWrong: String
    val markWrongDesc: String
    val sampleSaved: String
    val sampleFailed: String
    val scanAgain: String
    val play: String
    val pause: String
    val ttsUnavailable: String
    val ttsLanguageMissing: String
    val ttsFailed: String
    val failureTitle: String
    fun failure(reason: FailureReason): String
    fun documentFailure(reason: FailureReason): String
    val chooseAnotherDocument: String
    fun resultStats(blocks: Int, words: Int, ms: Long): String

    // Detail
    val blocksLabel: String
    val columnsLabel: String
    val markerLabel: String
    val correctionsLabel: String
    val columnShort: String

    // Read
    fun wordOf(index: Int, total: Int): String
    val previous: String
    val next: String
    val listen: String
    val readSettings: String
    val autoSpeak: String
    val autoSpeakDesc: String
    val autoSend: String
    val autoSendDesc: String
    val sendingWord: String
    val sentWord: String
    val padNotReady: String
    val readEmpty: String
    val swipeHint: String
    fun brailleFor(word: String): String
    val nextWordAction: String
    val previousWordAction: String

    // History
    val historyTitle: String
    val tagPhoto: String
    val tagDocument: String
    val tagSent: String
    val tagCorrected: String
    val historyEmptyTitle: String
    val historyEmptyDesc: String
    val entryDeleted: String
    val clearAll: String
    val clearAllTitle: String
    fun clearAllText(count: Int): String
    val swipeToDelete: String
    val untitled: String
    val openFailed: String

    // Settings
    val settingsTitle: String
    val deviceCard: String
    val manage: String
    val noDevice: String
    val languageCard: String
    val preferences: String
    val physicalOutput: String
    val physicalOutputDesc: String
    val audioGuide: String
    val audioGuideDesc: String
    val speechRate: String
    val slow: String
    val fast: String
    val voiceLanguage: String
    val testVoice: String
    val testVoiceSample: String
    val recognitionTitle: String
    val offlineTitle: String
    val offlineDesc: String
    val wordListLabel: String
    val wordListPlaceholder: String
    val geminiTitle: String
    val geminiDesc: String
    val apiKeyLabel: String
    val apiKeyPlaceholder: String
    val modelLabel: String
    val apiKeyNeeded: String
    val privacyTitle: String
    val trainingTitle: String
    val trainingDesc: String
    val storedTitle: String
    fun storedCount(count: Int): String
    val deleteAllSamples: String
    val deleteAllSamplesTitle: String
    fun deleteAllSamplesText(count: Int): String
    fun samplesDeleted(count: Int): String
    val showWelcome: String
    val offlinePackagesTitle: String
    val offlinePackagesDesc: String
    val packageScanner: String
    fun packageVoice(language: String): String
    val stateChecking: String
    fun stateDownloading(percent: Int?): String
    val stateReady: String
    val stateNeedsInternet: String
    val stateUnavailable: String
    val checkAgain: String
    val downloadVoices: String
    fun version(name: String): String

    // Connect
    val connectTitle: String
    val statusReady: String
    val statusOff: String
    val statusOffDesc: String
    val statusNotPaired: String
    val statusNotPairedDesc: String
    val statusPermission: String
    val statusPermissionDesc: String
    val statusUnsupported: String
    val statusUnsupportedDesc: String
    val turnOnBluetooth: String
    val allowAccess: String
    val openBluetoothSettings: String
    val deviceInfo: String
    val nameLabel: String
    val addressLabel: String
    val typeLabel: String
    val typeValue: String
    val selectionLabel: String
    val selectionAuto: String
    val selectionManual: String
    val lastSendLabel: String
    val never: String
    fun lastSend(bytes: Int, time: String): String
    val checkDevice: String
    val checking: String
    fun deviceOk(firmware: String): String
    val deviceOkOldFirmware: String
    val firmwareLabel: String
    val firmwareUnknown: String
    val pairPad: String
    val pairAnother: String
    fun pairingInProgress(name: String): String
    fun pairedOk(name: String): String
    val pairFailed: String
    val pairedDevices: String
    val refresh: String
    val use: String
    val inUse: String
    val noPairedDevices: String
    val likelyReceiver: String
    val otherDevice: String
    val resetSelection: String
    val pairHint: String

    // Bluetooth errors
    fun sendError(error: Esp32SendException): String
}

object StringsId : Strings {
    override val locale: Locale = Locale.forLanguageTag("id-ID")
    override val byDotCode = "oleh DotCode_"
    override val back = "Kembali"
    override val close = "Tutup"
    override val cancel = "Batal"
    override val retry = "Coba Lagi"
    override val delete = "Hapus"
    override val undo = "Urungkan"
    override val navHome = "Beranda"
    override val navScan = "Pindai"
    override val navHistory = "Riwayat"
    override val navSettings = "Pengaturan"
    override fun today(time: String) = "Hari ini, $time"
    override fun yesterday(time: String) = "Kemarin, $time"
    override fun words(count: Int) = "$count kata"

    override fun instruction(instruction: Instruction) = when (instruction) {
        Instruction.NO_TEXT -> "Arahkan kamera ke teks"
        Instruction.TOO_DARK -> "Kurang cahaya"
        Instruction.UNEVEN_LIGHT -> "Ada bayangan di halaman"
        Instruction.TILT -> "Luruskan ponsel di atas kertas"
        Instruction.SINGLE_PAGE -> "Terlihat dua halaman, dekatkan ke satu"
        Instruction.MOVE_LEFT -> "Geser ke kiri"
        Instruction.MOVE_RIGHT -> "Geser ke kanan"
        Instruction.MOVE_UP -> "Geser ke atas"
        Instruction.MOVE_DOWN -> "Geser ke bawah"
        Instruction.MOVE_CLOSER -> "Dekatkan ponsel"
        Instruction.MOVE_BACK -> "Jauhkan ponsel"
        Instruction.HOLD_STILL -> "Tahan, jangan bergerak"
        Instruction.READY -> "Pas. Tahan sebentar"
    }
    override val photoTaken = "Foto diambil"
    override val setupTitle = "Atur Aplikasi"
    override val setupSubtitle = "Beberapa pilihan singkat. Semua bisa diubah lagi di Pengaturan."
    override val setupHelpTitle = "Bantuan Saat Memakai"
    override val setupHelpDesc = "Aktif secara bawaan. Matikan yang tidak kamu perlukan."
    override val guidedCapture = "Panduan Memotret"
    override val guidedCaptureDesc = "Aplikasi menuntun arah kamera dan memotret sendiri saat teks sudah pas"
    override val spokenStatus = "Umumkan Status"
    override val spokenStatusDesc = "Proses, hasil, dan kesalahan diberitahukan dengan suara"
    override val hapticsTitle = "Getaran"
    override val hapticsDesc = "Getaran untuk berhasil, gagal, dan berpindah kata"
    override val afterScanTitle = "Setelah Memindai"
    override val afterScanDesc = "Apa yang terjadi begitu teks selesai dibaca."
    override fun afterScanOption(option: AppPrefs.AfterScan) = when (option) {
        AppPrefs.AfterScan.RESULT -> "Tampilkan hasil"
        AppPrefs.AfterScan.SPEAK -> "Langsung bacakan teksnya"
        AppPrefs.AfterScan.READ_MODE -> "Langsung buka Mode Baca"
    }
    override val setupAdvancedNote =
        "Koreksi Gemini dan penyimpanan data latih tetap mati. Keduanya bisa dinyalakan di Pengaturan."
    override val setupContinue = "Lanjut"
    override val openSetup = "Ulangi Pengaturan Awal"
    override val openTutorial = "Buka Panduan"
    override val skip = "Lewati"
    override fun tutorialStep(step: Int, total: Int) = "Langkah $step dari $total"
    override val tutorialOverviewTitle = "Cara Kerja Aplikasi"
    override val tutorialOverviewBody =
        "Pindai materi dengan kamera, lalu dengarkan teksnya, baca kata per kata, atau kirim ke BraillePad."
    override val tutorialHoldTitle = "Memegang Ponsel"
    override val tutorialHoldBody =
        "Letakkan kertas di meja. Tumpukan sikut di meja dan pegang ponsel sekitar 30 sentimeter di atas kertas, " +
            "sejajar dengan meja. Gerakkan perlahan; aplikasi akan memberi tahu arahnya."
    override val tutorialReadTitle = "Mode Baca"
    override val tutorialReadBody =
        "Di Mode Baca, teks dibaca kata per kata. Coba tombol Mundur, Dengar, dan Maju di bawah ini."
    override val tutorialFeedbackTitle = "Arti Getaran"
    override val tutorialFeedbackBody =
        "Dua getaran pendek berarti berhasil, satu getaran panjang berarti ada yang perlu diperbaiki, " +
            "dan ketukan kecil berarti berpindah kata. Coba rasakan ketiganya."
    override val tutorialSampleSentence = "Ibu pergi ke pasar."
    override val tutorialFinish = "Selesai"
    override val trySuccess = "Coba Berhasil"
    override val tryFailure = "Coba Gagal"
    override val tryTick = "Coba Ketukan"
    override val hapticsUnavailable = "Perangkat ini tidak memiliki getaran."
    override val hapticsOff = "Getaran dimatikan. Nyalakan di Pengaturan untuk mencobanya."

    override val onboardTitle = "Belajar Braille\nLebih Mudah"
    override val onboardSubtitle = "Baca Teks Materi Secara Mandiri"
    override val onboardButton = "Mulai Sekarang"

    override val deviceReady = "BraillePad Siap"
    override val deviceNotPaired = "BraillePad Belum Dipasangkan"
    override val deviceBluetoothOff = "Bluetooth Mati"
    override val devicePermission = "Izin Bluetooth Diperlukan"
    override val deviceUnsupported = "Bluetooth Tidak Tersedia"
    override val homeScanTitle = "Pindai Materi Baru"
    override val homeScanDesc = "Arahkan kamera ke teks untuk mengubahnya menjadi braille."
    override val homeScanButton = "Pindai Sekarang"
    override val homeUploadButton = "Unggah PDF/DOCX"
    override val statScanned = "Materi Dipindai"
    override val statWords = "Kata Terbaca"
    override val recentTitle = "Terakhir Dipindai"
    override val seeAll = "Lihat Semua"
    override val recentEmptyTitle = "Belum ada materi"
    override val recentEmptyDesc = "Hasil pindaian akan muncul di sini."

    override val scanHint = "Posisikan teks di dalam bingkai"
    override val autoScan = "Pindai Otomatis"
    override val recommended = "disarankan"
    override val gallery = "Galeri"
    override val document = "Dokumen"
    override val flashOn = "Nyalakan lampu"
    override val flashOff = "Matikan lampu"
    override val takePhoto = "Ambil foto"
    override val scannerUnavailable = "Pemindai dokumen tidak tersedia. Gunakan Ambil Foto."
    override val captureFailed = "Gagal mengambil foto. Coba lagi."
    override val cameraPermTitle = "Izin kamera diperlukan"
    override val cameraPermDesc =
        "Kamera dipakai untuk memotret materi. Kamu juga bisa memilih foto dari galeri atau mengunggah dokumen."
    override val cameraPermGrant = "Izinkan Kamera"
    override val openAppSettings = "Buka Pengaturan"

    override val procTitle = "Memproses Teks..."
    override val procDone = "Selesai!"
    override val procHint = "Tunggu sebentar, biasanya hanya beberapa detik."
    override val stepPrepareImage = "Membaca gambar"
    override val stepOcr = "Mendeteksi teks (OCR)"
    override val stepGemini = "Mengoreksi teks (Gemini)"
    override val stepOpenDocument = "Membuka dokumen"
    override val stepExtract = "Mengambil teks dokumen"
    override val stepBraille = "Mengonversi ke Braille"
    override val almostDone = "Hampir selesai…"
    override fun timeLeft(seconds: Int) =
        if (seconds >= 60) "Sekitar ${(seconds + 59) / 60} menit lagi" else "Sekitar $seconds detik lagi"

    override val resultTitle = "Hasil Konversi"
    override val tagReady = "Siap"
    override val tagFailed = "Gagal"
    override val originalText = "TEKS ASLI"
    override val beforeCorrection = "Sebelum Koreksi"
    override val afterCorrection = "Sesudah Koreksi"
    override val showAll = "Tampilkan Semua"
    override val showLess = "Ringkas"
    override val brailleOutput = "OUTPUT BRAILLE"
    override fun moreWordsInReadMode(count: Int) = "+$count kata lagi di Mode Baca"
    override val sendToPad = "Kirim ke BraillePad"
    override val sending = "Mengirim…"
    override fun sent(bytes: Int) = "Terkirim ($bytes byte)"
    override val sendFailed = "Gagal mengirim"
    override val openConnection = "Buka Koneksi"
    override val readMode = "Mode Baca"
    override val share = "Bagikan"
    override val exportText = "Ekspor Teks"
    override val exportTextDesc = "Teks biasa (.txt) untuk alat braille lain"
    override val exportJson = "Ekspor JSON"
    override val exportJsonDesc = "Data lengkap untuk diagnosis"
    override fun offlineFixes(count: Int) = "Koreksi offline: $count kata"
    override val geminiNoChange = "Dicek Gemini: tanpa perubahan"
    override fun geminiChanged(blocks: Int) = "Gemini: $blocks blok diubah"
    override val geminiFailed = "Koreksi Gemini gagal"
    override val moreTools = "Alat Lainnya"
    override val detailView = "Tampilan Detail"
    override val detailViewDesc = "Blok, peran, dan waktu proses"
    override val markWrong = "Tandai Hasil Salah & Simpan"
    override val markWrongDesc = "Simpan foto ini untuk perbaikan akurasi"
    override val sampleSaved = "Tersimpan untuk membantu perbaikan akurasi."
    override val sampleFailed = "Gagal menyimpan sampel. Coba lagi."
    override val scanAgain = "Pindai Lagi"
    override val play = "Putar suara"
    override val pause = "Jeda suara"
    override val ttsUnavailable = "Suara tidak tersedia di perangkat ini."
    override val ttsLanguageMissing = "Suara bahasa ini belum terpasang; memakai suara bawaan."
    override val ttsFailed =
        "Suara gagal diputar. Pastikan data suara sudah diunduh di Pengaturan > Text-to-speech, lalu coba lagi."
    override val failureTitle = "Teks tidak terbaca"
    override fun failure(reason: FailureReason) = when (reason) {
        FailureReason.TooBlurry -> "Foto kurang tajam. Tahan perangkat lebih stabil, lalu coba lagi."
        FailureReason.TooDark -> "Cahaya kurang. Dekatkan ke sumber cahaya, lalu coba lagi."
        FailureReason.NoTextFound -> "Tidak ada teks yang terbaca. Pastikan lembar kerja tampak penuh."
        FailureReason.ModelUnavailable -> "Mesin pengenalan teks tidak tersedia."
        FailureReason.Cancelled -> "Proses dibatalkan."
    }
    override fun documentFailure(reason: FailureReason) = when (reason) {
        FailureReason.NoTextFound ->
            "Tidak ada teks yang bisa diambil dari dokumen ini. Dokumen hasil pindaian (berupa gambar) perlu difoto dengan kamera."
        FailureReason.ModelUnavailable -> "Format berkas tidak didukung. Pilih berkas PDF atau DOCX."
        else -> failure(reason)
    }
    override val chooseAnotherDocument = "Pilih Dokumen Lain"
    override fun resultStats(blocks: Int, words: Int, ms: Long) = "$blocks blok · $words kata · $ms ms"

    override val blocksLabel = "blok"
    override val columnsLabel = "kolom"
    override val markerLabel = "penanda"
    override val correctionsLabel = "koreksi"
    override val columnShort = "kol"

    override fun wordOf(index: Int, total: Int) = "Kata $index/$total"
    override val previous = "Mundur"
    override val next = "Maju"
    override val listen = "Dengar"
    override val readSettings = "Pengaturan Baca"
    override val autoSpeak = "Bacakan otomatis"
    override val autoSpeakDesc = "Setiap kata dibacakan saat berpindah"
    override val autoSend = "Kirim otomatis ke BraillePad"
    override val autoSendDesc = "Setiap kata dikirim ke alat saat berpindah"
    override val sendingWord = "Mengirim ke BraillePad…"
    override val sentWord = "Terkirim ke BraillePad"
    override val padNotReady = "BraillePad belum siap"
    override val readEmpty = "Tidak ada teks untuk dibaca."
    override val swipeHint = "Geser ke kiri atau kanan untuk berpindah kata"
    override fun brailleFor(word: String) = "Braille untuk $word"
    override val nextWordAction = "Kata berikutnya"
    override val previousWordAction = "Kata sebelumnya"

    override val historyTitle = "Riwayat"
    override val tagPhoto = "Foto"
    override val tagDocument = "Dokumen"
    override val tagSent = "Terkirim"
    override val tagCorrected = "Dikoreksi"
    override val historyEmptyTitle = "Riwayat masih kosong"
    override val historyEmptyDesc = "Materi yang kamu pindai akan tersimpan di sini, hanya di perangkat ini."
    override val entryDeleted = "Riwayat dihapus"
    override val clearAll = "Hapus Semua"
    override val clearAllTitle = "Hapus semua riwayat?"
    override fun clearAllText(count: Int) = "$count materi akan dihapus dari perangkat ini secara permanen."
    override val swipeToDelete = "Geser untuk menghapus"
    override val untitled = "Tanpa judul"
    override val openFailed = "Materi ini tidak bisa dibuka."

    override val settingsTitle = "Pengaturan"
    override val deviceCard = "Perangkat Alat"
    override val manage = "Kelola"
    override val noDevice = "Belum ada perangkat"
    override val languageCard = "Bahasa / Language"
    override val preferences = "Preferensi"
    override val physicalOutput = "Output Braille Fisik"
    override val physicalOutputDesc = "Kirim setiap kata ke BraillePad di Mode Baca"
    override val audioGuide = "Audio Panduan (TTS)"
    override val audioGuideDesc = "Bacakan setiap kata di Mode Baca"
    override val speechRate = "Kecepatan Suara"
    override val slow = "Lambat"
    override val fast = "Cepat"
    override val voiceLanguage = "Bahasa Suara"
    override val testVoice = "Tes Suara"
    override val testVoiceSample = "Halo, ini suara BRaiLLE."
    override val recognitionTitle = "Pengenalan Teks"
    override val offlineTitle = "Koreksi Ejaan Offline"
    override val offlineDesc =
        "Gratis dan tanpa internet. Setiap halaman dibaca oleh dua mesin pengenal teks; kata yang " +
            "dibaca sama oleh keduanya tidak diubah. Kata yang diragukan dicocokkan dengan kamus " +
            "Bahasa Indonesia dan Inggris."
    override val wordListLabel = "Kata khusus (satu per baris)"
    override val wordListPlaceholder = "Istilah pelajaran atau nama yang tidak boleh dikoreksi"
    override val geminiTitle = "Koreksi Teks dengan Gemini"
    override val geminiDesc =
        "Opsional. Setelah dipindai, teks halaman dikirim ke Google Gemini untuk memperbaiki salah " +
            "baca berdasarkan konteks. Hanya teks yang dikirim, bukan foto. Membutuhkan internet dan " +
            "kunci API Gemini milikmu sendiri; setiap pemindaian memakai kuota kunci tersebut."
    override val apiKeyLabel = "Kunci API Gemini"
    override val apiKeyPlaceholder = "Dari aistudio.google.com"
    override val modelLabel = "Model"
    override val apiKeyNeeded = "Isi kunci API untuk mengaktifkan fitur ini."
    override val privacyTitle = "Data & Privasi"
    override val trainingTitle = "Bantu Tingkatkan Akurasi"
    override val trainingDesc =
        "Jika diaktifkan, kamu bisa menyimpan foto dan hasil teks di perangkat ini saat hasil bacaan " +
            "salah, untuk membantu perbaikan akurasi di masa depan. Semua data HANYA tersimpan di " +
            "perangkat ini — tidak pernah diunggah atau dikirim ke internet. Fitur ini mati secara " +
            "default dan bisa kamu matikan kapan saja."
    override val storedTitle = "Data Tersimpan"
    override fun storedCount(count: Int) = "$count sampel tersimpan di perangkat ini."
    override val deleteAllSamples = "Hapus Semua Sampel"
    override val deleteAllSamplesTitle = "Hapus semua sampel?"
    override fun deleteAllSamplesText(count: Int) =
        "Tindakan ini akan menghapus $count sampel yang tersimpan di perangkat ini secara permanen."
    override fun samplesDeleted(count: Int) = "$count sampel telah dihapus."
    override val showWelcome = "Tampilkan Layar Pembuka"
    override val offlinePackagesTitle = "Paket Offline"
    override val offlinePackagesDesc =
        "Diunduh otomatis sekali saat ada internet, supaya pemindai dan suara bisa dipakai tanpa internet."
    override val packageScanner = "Pemindai dokumen"
    override fun packageVoice(language: String) =
        if (language == AppPrefs.LANG_EN) "Suara bahasa Inggris" else "Suara bahasa Indonesia"
    override val stateChecking = "Memeriksa…"
    override fun stateDownloading(percent: Int?) = if (percent != null) "Mengunduh $percent%" else "Mengunduh…"
    override val stateReady = "Siap offline"
    override val stateNeedsInternet = "Butuh internet sekali"
    override val stateUnavailable = "Tidak tersedia"
    override val checkAgain = "Periksa Lagi"
    override val downloadVoices = "Unduh Suara"
    override fun version(name: String) = "Versi $name · DotCode_ 2026"

    override val connectTitle = "Hubungkan BraillePad"
    override val statusReady = "Siap Digunakan"
    override val statusOff = "Bluetooth Mati"
    override val statusOffDesc = "Nyalakan Bluetooth untuk mengirim teks ke BraillePad."
    override val statusNotPaired = "Belum Dipasangkan"
    override val statusNotPairedDesc =
        "Nyalakan ESP32 di dekat ponsel, lalu pasangkan dari sini."
    override val statusPermission = "Izin Diperlukan"
    override val statusPermissionDesc = "Izinkan akses Bluetooth agar aplikasi bisa melihat BraillePad."
    override val statusUnsupported = "Bluetooth Tidak Tersedia"
    override val statusUnsupportedDesc = "Perangkat ini tidak memiliki Bluetooth."
    override val turnOnBluetooth = "Nyalakan Bluetooth"
    override val allowAccess = "Izinkan Akses"
    override val openBluetoothSettings = "Buka Pengaturan Bluetooth"
    override val deviceInfo = "INFO PERANGKAT"
    override val nameLabel = "Nama"
    override val addressLabel = "Alamat"
    override val typeLabel = "Tipe"
    override val typeValue = "Bluetooth Klasik (SPP)"
    override val selectionLabel = "Pemilihan"
    override val selectionAuto = "Otomatis"
    override val selectionManual = "Dipilih manual"
    override val lastSendLabel = "Pengiriman Terakhir"
    override val never = "Belum pernah"
    override fun lastSend(bytes: Int, time: String) = "$bytes byte · $time"
    override val checkDevice = "Periksa Perangkat"
    override val checking = "Memeriksa…"
    override fun deviceOk(firmware: String) = "Terhubung · firmware $firmware"
    override val deviceOkOldFirmware =
        "Terhubung, tetapi firmware ESP32 ini belum melaporkan versinya. Perbarui firmware untuk melihatnya."
    override val firmwareLabel = "Firmware"
    override val firmwareUnknown = "Belum diperiksa"
    override val pairPad = "Pasangkan BraillePad"
    override val pairAnother = "Pasangkan Perangkat Lain"
    override fun pairingInProgress(name: String) = "Memasangkan $name… Konfirmasi di dialog yang muncul."
    override fun pairedOk(name: String) = "$name berhasil dipasangkan dan dipilih."
    override val pairFailed =
        "Pemasangan gagal. Pastikan ESP32 menyala dan dekat dengan ponsel, lalu coba lagi."
    override val pairedDevices = "Perangkat Terpasang"
    override val refresh = "Muat ulang"
    override val use = "Pilih"
    override val inUse = "Dipakai"
    override val noPairedDevices = "Belum ada perangkat yang dipasangkan."
    override val likelyReceiver = "Kemungkinan BraillePad"
    override val otherDevice = "Perangkat lain"
    override val resetSelection = "Kembali ke Pemilihan Otomatis"
    override val pairHint = "Nyalakan ESP32, lalu tekan Pasangkan BraillePad."

    override fun sendError(error: Esp32SendException) = when (error.reason) {
        Esp32SendException.Reason.UNSUPPORTED -> "Perangkat ini tidak mendukung Bluetooth."
        Esp32SendException.Reason.BLUETOOTH_OFF -> "Bluetooth mati. Nyalakan Bluetooth, lalu coba lagi."
        Esp32SendException.Reason.NOT_PAIRED ->
            "BraillePad belum dipasangkan. Buka Pengaturan > Bluetooth, pasangkan " +
                "\"${Esp32BluetoothSender.DEVICE_NAME}\", lalu coba lagi."
        Esp32SendException.Reason.CONNECT_FAILED ->
            "Gagal terhubung ke ${error.deviceName}. Pastikan ESP32 menyala dan tidak sedang " +
                "terhubung ke perangkat lain (misalnya laptop)."
        Esp32SendException.Reason.DISCONNECTED -> "Koneksi ke ESP32 terputus saat mengirim."
        Esp32SendException.Reason.PERMISSION -> "Izin Bluetooth diperlukan."
    }
}

object StringsEn : Strings {
    override val locale: Locale = Locale.ENGLISH
    override val byDotCode = "by DotCode_"
    override val back = "Back"
    override val close = "Close"
    override val cancel = "Cancel"
    override val retry = "Try Again"
    override val delete = "Delete"
    override val undo = "Undo"
    override val navHome = "Home"
    override val navScan = "Scan"
    override val navHistory = "History"
    override val navSettings = "Settings"
    override fun today(time: String) = "Today, $time"
    override fun yesterday(time: String) = "Yesterday, $time"
    override fun words(count: Int) = if (count == 1) "1 word" else "$count words"

    override fun instruction(instruction: Instruction) = when (instruction) {
        Instruction.NO_TEXT -> "Point the camera at text"
        Instruction.TOO_DARK -> "Not enough light"
        Instruction.UNEVEN_LIGHT -> "Shadow on the page"
        Instruction.TILT -> "Hold the phone flat over the paper"
        Instruction.SINGLE_PAGE -> "Two pages in view, move closer to one"
        Instruction.MOVE_LEFT -> "Move left"
        Instruction.MOVE_RIGHT -> "Move right"
        Instruction.MOVE_UP -> "Move up"
        Instruction.MOVE_DOWN -> "Move down"
        Instruction.MOVE_CLOSER -> "Move closer"
        Instruction.MOVE_BACK -> "Move back"
        Instruction.HOLD_STILL -> "Hold still"
        Instruction.READY -> "That's it. Hold steady"
    }
    override val photoTaken = "Photo taken"
    override val setupTitle = "Set Up the App"
    override val setupSubtitle = "A few quick choices. You can change them any time in Settings."
    override val setupHelpTitle = "Help While You Use It"
    override val setupHelpDesc = "On by default. Turn off whatever you don't need."
    override val guidedCapture = "Guided Photos"
    override val guidedCaptureDesc = "The app guides the camera and takes the photo when the text lines up"
    override val spokenStatus = "Announce Status"
    override val spokenStatusDesc = "Progress, results and errors are said out loud"
    override val hapticsTitle = "Vibration"
    override val hapticsDesc = "Vibration for success, failure and moving between words"
    override val afterScanTitle = "After Scanning"
    override val afterScanDesc = "What happens as soon as the text has been read."
    override fun afterScanOption(option: AppPrefs.AfterScan) = when (option) {
        AppPrefs.AfterScan.RESULT -> "Show the result"
        AppPrefs.AfterScan.SPEAK -> "Read the text aloud right away"
        AppPrefs.AfterScan.READ_MODE -> "Open Read Mode right away"
    }
    override val setupAdvancedNote =
        "Gemini correction and training-data saving stay off. You can turn either on in Settings."
    override val setupContinue = "Continue"
    override val openSetup = "Run Setup Again"
    override val openTutorial = "Open Tutorial"
    override val skip = "Skip"
    override fun tutorialStep(step: Int, total: Int) = "Step $step of $total"
    override val tutorialOverviewTitle = "How the App Works"
    override val tutorialOverviewBody =
        "Scan material with the camera, then listen to the text, read it word by word, or send it to the BraillePad."
    override val tutorialHoldTitle = "Holding the Phone"
    override val tutorialHoldBody =
        "Put the paper on a table. Rest your elbows on the table and hold the phone about 30 centimetres above the " +
            "paper, parallel to it. Move slowly; the app will tell you which way to go."
    override val tutorialReadTitle = "Read Mode"
    override val tutorialReadBody =
        "Read Mode goes word by word. Try the Back, Listen and Next buttons below."
    override val tutorialFeedbackTitle = "What the Vibrations Mean"
    override val tutorialFeedbackBody =
        "Two short buzzes mean success, one long buzz means something needs fixing, and a light tap means you moved " +
            "to another word. Try all three."
    override val tutorialSampleSentence = "Mother went to the market."
    override val tutorialFinish = "Finish"
    override val trySuccess = "Try Success"
    override val tryFailure = "Try Failure"
    override val tryTick = "Try Tap"
    override val hapticsUnavailable = "This phone has no vibration motor."
    override val hapticsOff = "Vibration is off. Turn it on in Settings to try it."

    override val onboardTitle = "Learn Braille\nThe Easy Way"
    override val onboardSubtitle = "Read Study Materials Independently"
    override val onboardButton = "Get Started"

    override val deviceReady = "BraillePad Ready"
    override val deviceNotPaired = "BraillePad Not Paired"
    override val deviceBluetoothOff = "Bluetooth Is Off"
    override val devicePermission = "Bluetooth Permission Needed"
    override val deviceUnsupported = "Bluetooth Unavailable"
    override val homeScanTitle = "Scan New Material"
    override val homeScanDesc = "Point your camera at text to convert it into braille."
    override val homeScanButton = "Scan Now"
    override val homeUploadButton = "Upload PDF/DOCX"
    override val statScanned = "Materials Scanned"
    override val statWords = "Words Read"
    override val recentTitle = "Recently Scanned"
    override val seeAll = "See All"
    override val recentEmptyTitle = "No materials yet"
    override val recentEmptyDesc = "Your scans will appear here."

    override val scanHint = "Position text inside the frame"
    override val autoScan = "Auto Scan"
    override val recommended = "recommended"
    override val gallery = "Gallery"
    override val document = "Document"
    override val flashOn = "Turn on light"
    override val flashOff = "Turn off light"
    override val takePhoto = "Take photo"
    override val scannerUnavailable = "Document scanner unavailable. Use Take Photo instead."
    override val captureFailed = "Couldn't take the photo. Try again."
    override val cameraPermTitle = "Camera permission needed"
    override val cameraPermDesc =
        "The camera is used to photograph study material. You can also pick a photo from the gallery or upload a document."
    override val cameraPermGrant = "Allow Camera"
    override val openAppSettings = "Open Settings"

    override val procTitle = "Processing Text..."
    override val procDone = "Done!"
    override val procHint = "Hang tight, this usually takes a few seconds."
    override val stepPrepareImage = "Reading image"
    override val stepOcr = "Detecting text (OCR)"
    override val stepGemini = "Correcting text (Gemini)"
    override val stepOpenDocument = "Opening document"
    override val stepExtract = "Extracting document text"
    override val stepBraille = "Converting to Braille"
    override val almostDone = "Almost done…"
    override fun timeLeft(seconds: Int) =
        if (seconds >= 60) "About ${(seconds + 59) / 60} min left" else "About $seconds s left"

    override val resultTitle = "Conversion Result"
    override val tagReady = "Ready"
    override val tagFailed = "Failed"
    override val originalText = "ORIGINAL TEXT"
    override val beforeCorrection = "Before Correction"
    override val afterCorrection = "After Correction"
    override val showAll = "Show All"
    override val showLess = "Show Less"
    override val brailleOutput = "BRAILLE OUTPUT"
    override fun moreWordsInReadMode(count: Int) = "+$count more words in Read Mode"
    override val sendToPad = "Send to BraillePad"
    override val sending = "Sending…"
    override fun sent(bytes: Int) = "Sent ($bytes bytes)"
    override val sendFailed = "Couldn't send"
    override val openConnection = "Open Connection"
    override val readMode = "Read Mode"
    override val share = "Share"
    override val exportText = "Export Text"
    override val exportTextDesc = "Plain text (.txt) for other braille tools"
    override val exportJson = "Export JSON"
    override val exportJsonDesc = "Full data for diagnosis"
    override fun offlineFixes(count: Int) = "Offline fixes: $count words"
    override val geminiNoChange = "Checked by Gemini: no changes"
    override fun geminiChanged(blocks: Int) = "Gemini: $blocks blocks changed"
    override val geminiFailed = "Gemini correction failed"
    override val moreTools = "More Tools"
    override val detailView = "Detail View"
    override val detailViewDesc = "Blocks, roles and processing times"
    override val markWrong = "Mark as Wrong & Save"
    override val markWrongDesc = "Keep this photo to help improve accuracy"
    override val sampleSaved = "Saved to help improve accuracy."
    override val sampleFailed = "Couldn't save the sample. Try again."
    override val scanAgain = "Scan Again"
    override val play = "Play audio"
    override val pause = "Pause audio"
    override val ttsUnavailable = "Text-to-speech is unavailable on this device."
    override val ttsLanguageMissing = "This voice language isn't installed; using the default voice."
    override val ttsFailed =
        "Couldn't play the voice. Make sure its voice data is downloaded in Settings > Text-to-speech, then try again."
    override val failureTitle = "Couldn't read the text"
    override fun failure(reason: FailureReason) = when (reason) {
        FailureReason.TooBlurry -> "The photo is blurry. Hold the device steadier and try again."
        FailureReason.TooDark -> "Not enough light. Move closer to a light source and try again."
        FailureReason.NoTextFound -> "No text was found. Make sure the whole worksheet is visible."
        FailureReason.ModelUnavailable -> "The text recognition engine is unavailable."
        FailureReason.Cancelled -> "Processing was cancelled."
    }
    override fun documentFailure(reason: FailureReason) = when (reason) {
        FailureReason.NoTextFound ->
            "No text could be taken from this document. A scanned document (an image) needs to be photographed with the camera."
        FailureReason.ModelUnavailable -> "This file type isn't supported. Choose a PDF or DOCX file."
        else -> failure(reason)
    }
    override val chooseAnotherDocument = "Choose Another Document"
    override fun resultStats(blocks: Int, words: Int, ms: Long) = "$blocks blocks · $words words · $ms ms"

    override val blocksLabel = "blocks"
    override val columnsLabel = "columns"
    override val markerLabel = "marker"
    override val correctionsLabel = "corrections"
    override val columnShort = "col"

    override fun wordOf(index: Int, total: Int) = "Word $index/$total"
    override val previous = "Back"
    override val next = "Next"
    override val listen = "Listen"
    override val readSettings = "Reading Settings"
    override val autoSpeak = "Read aloud automatically"
    override val autoSpeakDesc = "Each word is spoken as you move"
    override val autoSend = "Send to BraillePad automatically"
    override val autoSendDesc = "Each word is sent to the device as you move"
    override val sendingWord = "Sending to BraillePad…"
    override val sentWord = "Sent to BraillePad"
    override val padNotReady = "BraillePad isn't ready"
    override val readEmpty = "There's no text to read."
    override val swipeHint = "Swipe left or right to move between words"
    override fun brailleFor(word: String) = "Braille for $word"
    override val nextWordAction = "Next word"
    override val previousWordAction = "Previous word"

    override val historyTitle = "History"
    override val tagPhoto = "Photo"
    override val tagDocument = "Document"
    override val tagSent = "Sent"
    override val tagCorrected = "Corrected"
    override val historyEmptyTitle = "No history yet"
    override val historyEmptyDesc = "Materials you scan are kept here, on this device only."
    override val entryDeleted = "Entry deleted"
    override val clearAll = "Clear All"
    override val clearAllTitle = "Clear all history?"
    override fun clearAllText(count: Int) = "$count materials will be permanently deleted from this device."
    override val swipeToDelete = "Swipe to delete"
    override val untitled = "Untitled"
    override val openFailed = "This material couldn't be opened."

    override val settingsTitle = "Settings"
    override val deviceCard = "Device"
    override val manage = "Manage"
    override val noDevice = "No device yet"
    override val languageCard = "Language / Bahasa"
    override val preferences = "Preferences"
    override val physicalOutput = "Physical Braille Output"
    override val physicalOutputDesc = "Send each word to the BraillePad in Read Mode"
    override val audioGuide = "Audio Guide (TTS)"
    override val audioGuideDesc = "Speak each word in Read Mode"
    override val speechRate = "Speech Rate"
    override val slow = "Slow"
    override val fast = "Fast"
    override val voiceLanguage = "Voice Language"
    override val testVoice = "Test Voice"
    override val testVoiceSample = "Hello, this is the BRaiLLE voice."
    override val recognitionTitle = "Text Recognition"
    override val offlineTitle = "Offline Spelling Correction"
    override val offlineDesc =
        "Free and offline. Every page is read by two text recognizers; words both read the same way " +
            "are left alone. Doubtful words are checked against Indonesian and English dictionaries."
    override val wordListLabel = "Custom words (one per line)"
    override val wordListPlaceholder = "Subject terms or names that must not be corrected"
    override val geminiTitle = "Correct Text with Gemini"
    override val geminiDesc =
        "Optional. After a scan, the page text is sent to Google Gemini to fix misreadings from " +
            "context. Only text is sent, never the photo. Needs internet and your own Gemini API " +
            "key; every scan uses that key's quota."
    override val apiKeyLabel = "Gemini API key"
    override val apiKeyPlaceholder = "From aistudio.google.com"
    override val modelLabel = "Model"
    override val apiKeyNeeded = "Enter an API key to enable this feature."
    override val privacyTitle = "Data & Privacy"
    override val trainingTitle = "Help Improve Accuracy"
    override val trainingDesc =
        "When on, you can save the photo and recognized text on this device when a reading is " +
            "wrong, to help improve accuracy later. Everything stays ONLY on this device — it is " +
            "never uploaded or sent over the internet. Off by default; turn it off any time."
    override val storedTitle = "Stored Data"
    override fun storedCount(count: Int) = "$count samples stored on this device."
    override val deleteAllSamples = "Delete All Samples"
    override val deleteAllSamplesTitle = "Delete all samples?"
    override fun deleteAllSamplesText(count: Int) =
        "This permanently deletes the $count samples stored on this device."
    override fun samplesDeleted(count: Int) = "$count samples deleted."
    override val showWelcome = "Show Welcome Screen"
    override val offlinePackagesTitle = "Offline Packages"
    override val offlinePackagesDesc =
        "Downloaded automatically once while online, so the scanner and voices work without internet."
    override val packageScanner = "Document scanner"
    override fun packageVoice(language: String) =
        if (language == AppPrefs.LANG_EN) "English voice" else "Indonesian voice"
    override val stateChecking = "Checking…"
    override fun stateDownloading(percent: Int?) = if (percent != null) "Downloading $percent%" else "Downloading…"
    override val stateReady = "Ready offline"
    override val stateNeedsInternet = "Needs internet once"
    override val stateUnavailable = "Not available"
    override val checkAgain = "Check Again"
    override val downloadVoices = "Download Voices"
    override fun version(name: String) = "Version $name · DotCode_ 2026"

    override val connectTitle = "Connect BraillePad"
    override val statusReady = "Ready to Use"
    override val statusOff = "Bluetooth Is Off"
    override val statusOffDesc = "Turn on Bluetooth to send text to the BraillePad."
    override val statusNotPaired = "Not Paired"
    override val statusNotPairedDesc = "Turn on the ESP32 near the phone, then pair it from here."
    override val statusPermission = "Permission Needed"
    override val statusPermissionDesc = "Allow Bluetooth access so the app can see your BraillePad."
    override val statusUnsupported = "Bluetooth Unavailable"
    override val statusUnsupportedDesc = "This device has no Bluetooth."
    override val turnOnBluetooth = "Turn On Bluetooth"
    override val allowAccess = "Allow Access"
    override val openBluetoothSettings = "Open Bluetooth Settings"
    override val deviceInfo = "DEVICE INFO"
    override val nameLabel = "Name"
    override val addressLabel = "Address"
    override val typeLabel = "Type"
    override val typeValue = "Classic Bluetooth (SPP)"
    override val selectionLabel = "Selection"
    override val selectionAuto = "Automatic"
    override val selectionManual = "Chosen manually"
    override val lastSendLabel = "Last Send"
    override val never = "Never"
    override fun lastSend(bytes: Int, time: String) = "$bytes bytes · $time"
    override val checkDevice = "Check Device"
    override val checking = "Checking…"
    override fun deviceOk(firmware: String) = "Connected · firmware $firmware"
    override val deviceOkOldFirmware =
        "Connected, but this ESP32 firmware doesn't report its version yet. Update the firmware to see it."
    override val firmwareLabel = "Firmware"
    override val firmwareUnknown = "Not checked yet"
    override val pairPad = "Pair BraillePad"
    override val pairAnother = "Pair Another Device"
    override fun pairingInProgress(name: String) = "Pairing with $name… Confirm in the dialog that appears."
    override fun pairedOk(name: String) = "$name is paired and selected."
    override val pairFailed =
        "Pairing failed. Make sure the ESP32 is on and near the phone, then try again."
    override val pairedDevices = "Paired Devices"
    override val refresh = "Refresh"
    override val use = "Use"
    override val inUse = "In Use"
    override val noPairedDevices = "No paired devices yet."
    override val likelyReceiver = "Likely BraillePad"
    override val otherDevice = "Other device"
    override val resetSelection = "Reset to Automatic Selection"
    override val pairHint = "Turn on the ESP32, then tap Pair BraillePad."

    override fun sendError(error: Esp32SendException) = when (error.reason) {
        Esp32SendException.Reason.UNSUPPORTED -> "This device doesn't support Bluetooth."
        Esp32SendException.Reason.BLUETOOTH_OFF -> "Bluetooth is off. Turn it on and try again."
        Esp32SendException.Reason.NOT_PAIRED ->
            "The BraillePad isn't paired. Open Settings > Bluetooth, pair " +
                "\"${Esp32BluetoothSender.DEVICE_NAME}\", then try again."
        Esp32SendException.Reason.CONNECT_FAILED ->
            "Couldn't connect to ${error.deviceName}. Make sure the ESP32 is on and not connected " +
                "to another device (such as a laptop)."
        Esp32SendException.Reason.DISCONNECTED -> "The connection to the ESP32 dropped while sending."
        Esp32SendException.Reason.PERMISSION -> "Bluetooth permission is needed."
    }
}

fun stringsFor(language: String): Strings = if (language == AppPrefs.LANG_EN) StringsEn else StringsId

val LocalStrings = staticCompositionLocalOf<Strings> { StringsId }
