# Quizo
Vokabel und Sätzetrainer
Zunächst müssen folgende Programme gestartet werden: Tomcat, Ngrok. 
Den Alexa Skill kann man mit den Worten "Quizo lass uns etwas lernen" starten.
Daraufhin startet das System und liest die Spielanleitung vor. Außerdem fragt Alexa, ob man Vokabeln oder Sätze lernen möchte.
Der User entscheidet sich individuell und sagt entweder "Sätze" oder "Vokabeln".
Hat der User eine der beiden Möglichkeiten ausgewählt, stellt Alexa zufällige Fragen aus der Datenbank aus SQLite.
Die Frage, welche Alexa stellt, beihnaltet sowohl die Frage als auch vier Antwortmöglichkeiten.
Der User sollte nun mit einem der vier Antwortmöglichkeiten A,B,C oder D antworten.
Das Programm kann jederzeit mit den Worten "Quizo ich möchte aufhören" beendet werden.
Für eine Erweiterung des Skills, in Bezug auf mehr Fragen, müsste die Datenbank in SQLite erweitert werden.
Für eine Verbesserung der Kommunikation zwischen Alexa und dem User, müssten mehr Pattern in Eclipse erstellt werden.
