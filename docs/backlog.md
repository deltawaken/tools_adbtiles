# ADB Tiles — à faire

## Montrer l'adresse IP

**Demandé par le porteur, 2026-10-16.** La tuile ne montre pas l'adresse IP du téléphone, et sans elle
on ne peut pas s'y connecter : `adb connect <ip>:5555` exige de la connaître, et elle change avec le
bail DHCP.

Ce jour-là, le Jelly Max répondait au ping sur `192.168.27.65` le matin et refusait tous les ports
l'après-midi — l'adresse avait changé, la tuile était bien active, et rien à l'écran ne permettait de
le voir. L'installation d'un APK a été bloquée pour cette seule raison.

Attendu : la tuile (ou son libellé, ou sa fenêtre de détail) affiche `<ip>:<port>` quand l'écoute est
ouverte.
