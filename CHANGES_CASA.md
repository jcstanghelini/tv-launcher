# Casa TV

Fork personal de [tvOS Launcher](https://github.com/techgenius1/tvOS-Launcher) (Apache 2.0) para el onn de casa.

## Cambios respecto del original

- **Paquete propio** (`com.jcstanghelini.tvlauncher`): convive con el launcher original y no lo pisa.
- **Actualizaciones** desde los Releases de este repo, no del original.
- **CleanYourTV** fijado a un commit revisado (v3.6.0) en vez de bajar siempre "lo último".
- **Horarios de oración** fuera de los widgets y de Ajustes.
- **Agenda familiar**: widget con los próximos eventos del Google Calendar familiar (link secreto iCal). Íconos para partidos, cumpleaños, viajes, turnos y colegio.
- **Mis equipos**: Boca y Estudiantes de Caseros (Primera Nacional) con datos de ESPN: en vivo, próximo partido o último resultado.
- **Fútbol**: Boca como favorito y ligas argentinas, Libertadores y Sudamericana en la búsqueda.
- **Clima** en Caseros por defecto.
- **Protector de pantalla con fotos** de una carpeta de Google Drive, con reloj y fecha.

## Archivos modificados o nuevos

Modificados: `AndroidManifest.xml`, `build.sh`, `AppUpdater.java`, `CleanYourTv.java`, `HomeDashboard.java`,
`I18n.java`, `MainActivity.java`, `SettingsActivity.java`, `Weather.java`.
Paquete Java movido de `com.techgenius.tvoslauncher` a `com.jcstanghelini.tvlauncher` (todos los archivos de `src/`).
Nuevos: `Familia.java`, `FotosView.java`, `FotosDream.java`, `FotosActivity.java`, `.github/workflows/build.yml`,
`signing/release.jks.enc`.

## Configuración en la tele

Ajustes › Widgets de inicio › Casa:

1. **Link del calendario familiar**: en Google Calendar desde la compu, Configuración › el calendario familiar ›
   "Dirección secreta en formato iCal". Se pega con el teclado de la app Google TV del celular.
2. **Carpeta de fotos**: una carpeta de Drive compartida como "Cualquier persona con el vínculo".
3. **Usar fotos como protector de pantalla**.
