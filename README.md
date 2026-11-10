# Phonon Reader (Android)

Application Android native pour extraire et synthétiser vocalement n'importe quel article de `wol.jw.org` en local via Phonon (Gradium) et ONNX Runtime.

## Option B : APK léger avec téléchargement au premier démarrage
- Poids de l'APK : ~15 Mo.
- Au premier lancement de l'application, un écran dédié télécharge les poids du modèle ONNX (~120 Mo) en Wi-Fi.
- Une fois le modèle récupéré, la synthèse fonctionne 100% hors-ligne.

## Comment obtenir l'APK automatiquement via GitHub :
1. Crée un nouveau dépôt sur GitHub (ex: `phonon-reader`).
2. Décompresse l'archive et envoie tous les fichiers sur la branche `main`.
3. GitHub Actions démarre immédiatement la compilation.
4. Dans l'onglet **Actions**, clique sur le workflow terminé et télécharge l'artefact **PhononReader-APK**.
