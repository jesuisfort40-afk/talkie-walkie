# 📡 Talkie Walkie WiFi — Guide Complet

## 🚀 Comment obtenir l'APK en 5 étapes

### ÉTAPE 1 — Créer compte GitHub
1. Va sur **github.com**
2. Clique **Sign up** (gratuit)
3. Crée ton compte

### ÉTAPE 2 — Créer un nouveau dépôt
1. Clique le **+** en haut à droite
2. Sélectionne **New repository**
3. Nom : `talkie-walkie`
4. Sélectionne **Public**
5. Clique **Create repository**

### ÉTAPE 3 — Upload les fichiers
1. Dans ton dépôt, clique **uploading an existing file**
2. Glisse-dépose TOUS les fichiers du projet
3. Respecte la structure des dossiers !
4. Clique **Commit changes**

### ÉTAPE 4 — GitHub compile automatiquement !
1. Va dans l'onglet **Actions** de ton dépôt
2. Tu verras **Build APK** en cours (⏳ ~3-5 minutes)
3. Attends que le cercle devienne ✅ vert

### ÉTAPE 5 — Télécharger l'APK
1. Clique sur le workflow **Build APK**
2. En bas de la page → **Artifacts**
3. Clique **TalkieWalkie-APK** pour télécharger
4. Installe sur ton Huawei Y9 !

---

## 📱 Comment utiliser l'app

### Mode WiFi Local (~100m)
- Les 2 téléphones sur le même WiFi
- Téléphone A : note son IP (affiché en haut de l'app)
- Téléphone B : entre l'IP du téléphone A
- Maintiens le bouton vert pour parler !

### Mode WiFi Direct (~200m, sans routeur)
- Paramètres Android → WiFi → WiFi Direct
- Connecte les 2 téléphones ensemble
- L'IP du groupe owner est toujours **192.168.49.1**
- Entre cette IP dans l'app

### Mode Internet (partout dans le monde)
- Les 2 téléphones entrent la même IP serveur
- Utilisez le même **code salle** (ex: ALPHA42)
- Besoin d'un serveur relay (PC ou VPS)

---

## ⚙️ Specs techniques
- **Audio** : PCM 16-bit, 16kHz, Mono
- **Protocole** : TCP Socket
- **Port** : 55555
- **Latence** : ~50-200ms selon le mode
- **Compatible** : Android 5.0+ (API 21)
