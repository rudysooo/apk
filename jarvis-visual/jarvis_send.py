"""Envoie une commande au visuel Jarvis qui tourne (UDP local).

Exemples :
    python jarvis_send.py state thinking
    python jarvis_send.py say "J'ai trouvé 3 résultats."
    python jarvis_send.py level 0.6
    python jarvis_send.py perch

Depuis ton propre programme Jarvis, il suffit d'envoyer le même texte en UDP :
    import socket
    socket.socket(socket.AF_INET, socket.SOCK_DGRAM).sendto(b"state listening", ("127.0.0.1", 8790))
"""
import socket
import sys

if len(sys.argv) < 2:
    print(__doc__)
    sys.exit(1)
msg = " ".join(sys.argv[1:])
socket.socket(socket.AF_INET, socket.SOCK_DGRAM).sendto(msg.encode("utf-8"), ("127.0.0.1", 8790))
