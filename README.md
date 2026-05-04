# EDS Overlay (Muavin): Akıllı Trafik Denetleme ve Güvenlik Uyarı Sistemi

Bu proje, **İstanbul Teknik Üniversitesi Bilgisayar Mühendisliği** bölümü öğrencisi **Tufan Kaan İsli** tarafından eğitim ve akademik araştırma amaçlı geliştirilmiş bir mobil platform çözümüdür. Uygulama, Elektronik Denetleme Sistemleri (EDS) verilerini kullanarak sürücü güvenliğini artırmayı ve trafik bilincini geliştirmeyi hedeflemektedir.

## Akademik Bağlam ve Amaç

Projenin temel amacı, coğrafi bilgi sistemleri (GIS), gerçek zamanlı veri işleme ve mobil sensör füzyonu tekniklerini kullanarak bir "akıllı asistan" yapısı kurgulamaktır. Çalışma kapsamında aşağıdaki akademik problem çözümlerine odaklanılmıştır:

- **Mekansal İndeksleme:** Büyük ölçekli koordinat verileri üzerinde düşük gecikmeli sorgulama.
- **Vektörel Yönelim Filtreleme:** Kullanıcının sadece hareket yönündeki tehditleri algılayan trigonometrik modellerin geliştirilmesi.
- **Batarya Optimizasyonu:** Arka planda sürekli çalışan servislerin Android yaşam döngüsü kurallarına (Doze mode, Foreground limits) uygun şekilde optimize edilmesi.

---

## Teknik Mimari

Proje, modern yazılım mimarisi prensipleri doğrultusunda yapılandırılmış bir Android uygulamasından (Mobil İstemci) oluşmaktadır. Geliştirme sürecinde ihtiyaç duyulan veriler dışarıdan temin edilerek sisteme entegre edilmiştir.

### 1. Veri Edinimi (Scraping)
Uygulama kapsamında kullanılan hız kamerası lokasyon verileri, Emniyet Genel Müdürlüğü (EGM) açık harita servislerinden Selenium tabanlı dinamik web scraping ve HTTP request teknikleri kullanılarak elde edilmiş ve normalize edildikten sonra uygulamanın yerel kaynaklarına (`assets/eds_data.json`) entegre edilmiştir.

### 2. Mobil Uygulama Katmanı (Android & Kotlin)
Uygulama, yüksek performanslı mekansal hesaplamalar için özelleştirilmiş bir motor barındırır.

#### A. Mekansal Hesaplama Motoru (Spatial Engine)
Hatalı pozitif uyarıları (paralel yollar, ters istikametteki kameralar) engellemek için iki aşamalı bir filtreleme uygulanır:
- **Haversine Formülü:** Küresel yüzey üzerindeki iki nokta arasındaki büyük daire mesafesini (Great-circle distance) hesaplar.
- **Azimut (Rulman) Analizi:** Kullanıcının hareket vektörü ile EDS kamerasının bakış açısı arasındaki açısal farkı analiz eder. `θ < 25°` tolerans aralığındaki kameralar "tehdit" olarak nitelendirilir.

#### B. Veri Yönetimi ve Kalıcılık
- **Room Persistence Library:** EDS verileri SQLite tabanlı bir veritabanında saklanır.
- **Bounding Box Query:** Performans için önce bir sınırlayıcı kutu sorgusu (Latitude/Longitude range) yapılarak aday noktalar küçültülür, ardından maliyetli trigonometrik hesaplamalar sadece bu küme üzerinde çalıştırılır.

#### C. Arka Plan Servisi ve UI (Overlay Service)
- **Foreground Service:** Uygulama kapalıyken dahi çalışmaya devam eden, düşük bellek öncelikli bir servis mimarisi.
- **Custom View Animation:** `GlowParticlesView` ile ortam ışıklandırması ve görsel uyarılar için optimize edilmiş, donanım hızlandırmalı bir animasyon motoru (Spotify ambient tarzı) kullanılmıştır.

#### D. Geri Bildirim ve Gizlilik Mimarisi (Privacy-First Feedback)
Sistemin kitle kaynaklı veri toplama (eksik/hatalı radar bildirimi) altyapısı, ağ izolasyonu (network isolation) prensibiyle tasarlanmıştır:
- **İnternet Bağımsızlığı:** Uygulama hiçbir şekilde `INTERNET` izni talep etmez. İletişim, `Intent.ACTION_SENDTO` kullanılarak işletim sistemine devredilir ve veri bütünlüğü kullanıcının kendi mail istemcisi üzerinden sağlanır.
- **Opt-in Veri İşleme:** Hata tespiti için gereken GPS verileri, yalnızca kullanıcının açık rızasıyla ve anlık `lastKnownLocation` kullanılarak elde edilir. Tüm konum ve donanım verileri telefon içerisinde işlenerek cihaz dışına izinsiz çıkarılmaz.
- **Dinamik Ekran Adaptasyonu:** `WindowInsets` API kullanılarak küçük ekranlı cihazlar ve klavye (IME) etkileşimleri için otonom kaydırma (auto-scroll) ve duyarlı (responsive) layout mekanizmaları eklenmiştir.

---

## Matematiksel Temeller

Sistemde kullanılan mesafe hesaplama algoritması şu şekildedir:

$$d = 2r \arcsin\left(\sqrt{\sin^2\left(\frac{\phi_2 - \phi_1}{2}\right) + \cos(\phi_1) \cos(\phi_2) \sin^2\left(\frac{\lambda_2 - \lambda_1}{2}\right)}\right)$$

Burada $\phi$ enlem, $\lambda$ boylam ve $r$ Dünya yarıçapıdır (6,371 km).

---

## Güvenlik ve Etik Bildirimi

Bu uygulama, **kesinlikle trafik kurallarını ihlal etmeye teşvik amacı gütmemektedir.** Aksine, sürücülerin hız limitlerine uymasını sağlayarak trafik güvenliğini artırmak ve ani frenleme gibi riskli davranışları azaltmak için tasarlanmıştır. Kullanılan veriler eğitim amaçlıdır ve akademik bir projenin çıktısıdır.

> [!IMPORTANT]
> Proje ile ilgili herhangi bir telif hakkı, veri kullanımı veya güvenlik endişesi durumunda lütfen akademik danışmanım veya şahsım ile iletişime geçiniz.

---

## İletişim

**Geliştirici:** Tufan Kaan İsli  
**Kurum:** İstanbul Teknik Üniversitesi (İTÜ), Bilgisayar Mühendisliği  
**E-posta:** [isli23@itu.edu.tr](mailto:isli23@itu.edu.tr)

© 2026 - Eğitim Amaçlı Proje Geliştirme Çalışması
