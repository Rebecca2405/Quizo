/**
    Copyright 2014-2015 Amazon.com, Inc. or its affiliates. All Rights Reserved.

    Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file except in compliance with the License. A copy of the License is located at

        http://aws.amazon.com/apache2.0/

    or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
 */
package com.amazon.customskill;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazon.speech.json.SpeechletRequestEnvelope;
import com.amazon.speech.slu.Intent;
import com.amazon.speech.speechlet.IntentRequest;
import com.amazon.speech.speechlet.LaunchRequest;
import com.amazon.speech.speechlet.SessionEndedRequest;
import com.amazon.speech.speechlet.SessionStartedRequest;
import com.amazon.speech.speechlet.SpeechletResponse;
import com.amazon.speech.speechlet.SpeechletV2;
import com.amazon.speech.ui.PlainTextOutputSpeech;
import com.amazon.speech.ui.Reprompt;
import com.amazon.speech.ui.SsmlOutputSpeech;

/*
 * This class is the actual skill. Here you receive the input and have to produce the speech output. 
 */
public class AlexaSkillSpeechlet implements SpeechletV2 {
	// Initialisiert den Logger. Am besten möglichst of Logmeldungen erstellen,
	// hilft hinterher bei der Fehlersuche!
	static Logger logger = LoggerFactory.getLogger(AlexaSkillSpeechlet.class);

	// Zähler für richtige und falsche Antworten
	static int richtigeAntworten = 0;
	static int falscheAntworten = 0;

	// ausgelesene Frage- und Antwortmöglichkeiten
	static String antwortA = "";
	static String antwortB = "";
	static String antwortC = "";
	static String antwortD = "";
	static String frage = "";

	// erwarteter Intent für die korrekte Antwort
	static UserIntent korrekteAntwortIntent = null;

	// Was der User gesagt hat
	public static String userRequest;

	// In welchem Spracherkennerknoten sind wir?
	static enum RecognitionState {
		Anfangsfrage, Quizfrage
	};

	RecognitionState recState;

	static String userResponse = "";

	// Ob der User sich für Vokabeln oder Sätze entscheidet
	VokabelnOderSaetze status;

	UserIntent ourUserIntent;

	// Baut die Systemäußerung zusammen
	String buildString(String msg, String replacement1, String replacement2) {
		return msg.replace("{replacement}", replacement1).replace("{replacement2}", replacement2);
	}

	// Datenbank für Quizfragen
	private static Connection connection = null;

	// Vorgegebene Methode wird am Anfang einmal ausgeführt, wenn ein neuer Dialog
	// startet:
	// * lies Nutzeräußerungen ein
	// * Initialisiere Variablen
	@Override
	public void onSessionStarted(SpeechletRequestEnvelope<SessionStartedRequest> requestEnvelope) {
		logger.info("Alexa session begins");
	}

	// Wir starten den Dialog:
	// * Hole die erste Frage aus der Datenbank
	// * Lies die Welcome-Message vor, dann die Frage
	// * Dann wollen wir eine Antwort erkennen
	@Override
	public SpeechletResponse onLaunch(SpeechletRequestEnvelope<LaunchRequest> requestEnvelope) {
		logger.info("onLaunch");
		recState = RecognitionState.Anfangsfrage;
		return askUserResponse(
				"Schön, dass du da bist. Ich bin dein persönlicher Vokabeltrainer. Du kannst zu Beginn zwischen einzelnen Vokabeln und ganzen Sätzen wählen, die ich dich dann abfrage. Zu jeder Frage erhälst du vier Antwortmöglichkeiten. Du kannst mit den Buchstaben A, B, C oder D antworten. Möchtest du Vokabeln oder Sätze lernen?");
	}

	// Hier gehen wir rein, wenn der User etwas gesagt hat
	// Wir speichern den String in userRequest, je nach recognition State reagiert
	// das System unterschiedlich
	@Override
	public SpeechletResponse onIntent(SpeechletRequestEnvelope<IntentRequest> requestEnvelope) {
		IntentRequest request = requestEnvelope.getRequest();
		Intent intent = request.getIntent();
		userRequest = intent.getSlot("anything").getValue();
		logger.info("Received following text: [" + userRequest + "]");
		logger.info("recState is [" + recState + "]");

		recognizeUserIntent(userRequest);
		if (ourUserIntent == UserIntent.Abbrechen) {
			return tellUserAndFinish("Du hast " + richtigeAntworten + " von " + (richtigeAntworten + falscheAntworten)
					+ " Fragen richtig beantwortet. Bis zum nächsten Mal.");
		}
		if (ourUserIntent == UserIntent.Error) {
			return tellUserAndFinish(
					"Ich habe deine Eingabe nicht verstanden. Du hast folgendes gesagt " + userResponse);
		}
		switch (recState) {
		case Anfangsfrage:
			return beantworteAnfangsfrage(userRequest);

		case Quizfrage:
			return beantworteQuizfrage(userRequest);

		}
		return tellUserAndFinish("On Intent ");
	}

	private SpeechletResponse beantworteQuizfrage(String userRequest) {

		if (ourUserIntent == korrekteAntwortIntent) {
			richtigeAntworten = richtigeAntworten + 1;
			return stelleEineFrage("Super. Das war richtig. ");

		} else {
			String korrekteAntwort = "";
			switch (korrekteAntwortIntent) {
			case A:
				korrekteAntwort = antwortA;
				break;
			case B:
				korrekteAntwort = antwortB;
				break;
			case C:
				korrekteAntwort = antwortC;
				break;
			case D:
				korrekteAntwort = antwortD;
				break;
			default:
				break;
			}
			falscheAntworten = falscheAntworten + 1;
			return stelleEineFrage("Das war leider falsch. Die richtige Antwort lautet: " + korrekteAntwort
					+ " . Machen wir mit der nächsten Frage weiter. ");
		}

	}

	private SpeechletResponse beantworteAnfangsfrage(String userRequest) {

		if (ourUserIntent == UserIntent.Saetze) {
			status = VokabelnOderSaetze.Saetze;
			return stelleEineFrage("");
		}
		if (ourUserIntent == UserIntent.Vokabeln) {
			status = VokabelnOderSaetze.Vokabeln;
			return stelleEineFrage("");
		}
		return tellUserAndFinish("Dein Userintent ist: " + ourUserIntent);
	}

	// alle Fragen werden aus der Datenbank gelesen und anschließend randomisiert
	// eine Frage ausgewählt
	private SpeechletResponse stelleEineFrage(String prefix) {
		recState = RecognitionState.Quizfrage;
		String typ = "";
		if (status == VokabelnOderSaetze.Saetze) {
			typ = "Sätze";
		}
		if (status == VokabelnOderSaetze.Vokabeln) {
			typ = "Vokabeln";
		}

		try {
			connection = DBConnection.getConnection();
			Statement stmt = connection.createStatement();
			ResultSet rs = stmt.executeQuery("select * from " + typ);

			LinkedList<Frage> fragen = new LinkedList<Frage>();

			while (rs.next()) {
				Frage frageObject = new Frage();
				frageObject.setFrage(rs.getString("Frage"));
				frageObject.setRichtig(rs.getString("Richtig"));
				frageObject.setAntwort1(rs.getString("Antwort 1"));
				frageObject.setAntwort2(rs.getString("Antwort 2"));
				frageObject.setAntwort3(rs.getString("Antwort 3"));
				frageObject.setAntwort4(rs.getString("Antwort 4"));
				fragen.add(frageObject);
			}

			int randomNum = ThreadLocalRandom.current().nextInt(0, fragen.size() - 1);
			Frage selectedQuestion = fragen.get(randomNum);

			// Fragen und Antworten werden global gespeichert
			frage = selectedQuestion.getFrage();

			switch (selectedQuestion.getRichtig()) {
			case "A":
				korrekteAntwortIntent = UserIntent.A;
				break;
			case "B":
				korrekteAntwortIntent = UserIntent.B;
				break;
			case "C":
				korrekteAntwortIntent = UserIntent.C;
				break;
			case "D":
				korrekteAntwortIntent = UserIntent.D;
				break;
			}

			antwortA = selectedQuestion.getAntwort1();
			antwortB = selectedQuestion.getAntwort2();
			antwortC = selectedQuestion.getAntwort3();
			antwortD = selectedQuestion.getAntwort4();

			return askUserResponse(prefix + "Was bedeutet " + frage + " auf Englisch? Antwort a: " + antwortA
					+ ", Antwort b: " + antwortB + ", Antwort c: " + antwortC + ", Antwort d: " + antwortD);
		} catch (Exception e) {
			return tellUserAndFinish(e.getMessage());
		}
	}

	// Interpretieren der Usereingabe
	void recognizeUserIntent(String userRequest) {
		userRequest = userRequest.toLowerCase().trim();
		userResponse = userRequest;
		String pattern1 = "(ich nehme )?(antwort )?(\\b[a-d]\\b)( bitte)?";
		String pattern2 = "(vokabeln){1}";
		String pattern3 = "(sätze)|(saetze)|(setze){1}";
		String pattern4 = "(ich möchte )?(aufhören){1}";
		String pattern5 = "(ich möchte )?(quizo)(beenden){1}";
		String pattern6 = "(ich habe )? (keine lust mehr )(quizo zu spielen)?";
		String pattern7 = "(ich möchte )?(vokabeln )(heute )?(lernen)?";
		String pattern8 = "(ich möchte )?(heute )?(sätze )(lernen)?";

		Pattern p1 = Pattern.compile(pattern1);
		Matcher m1 = p1.matcher(userRequest);
		Pattern p2 = Pattern.compile(pattern2);
		Matcher m2 = p2.matcher(userRequest);
		Pattern p3 = Pattern.compile(pattern3);
		Matcher m3 = p3.matcher(userRequest);
		Pattern p4 = Pattern.compile(pattern4);
		Matcher m4 = p4.matcher(userRequest);
		Pattern p5 = Pattern.compile(pattern5);
		Matcher m5 = p5.matcher(userRequest);
		Pattern p6 = Pattern.compile(pattern6);
		Matcher m6 = p6.matcher(userRequest);
		Pattern p7 = Pattern.compile(pattern7);
		Matcher m7 = p7.matcher(userRequest);
		Pattern p8 = Pattern.compile(pattern8);
		Matcher m8 = p8.matcher(userRequest);

		if (m1.find()) {
			String answer = m1.group(3);
			switch (answer) {
			case "a":
				ourUserIntent = UserIntent.A;
				break;
			case "b":
				ourUserIntent = UserIntent.B;
				break;
			case "c":
				ourUserIntent = UserIntent.C;
				break;
			case "d":
				ourUserIntent = UserIntent.D;
				break;
			}
		} else if (m2.find() || m7.find()) {
			ourUserIntent = UserIntent.Vokabeln;
		} else if (m8.find() || m3.find()) {
			ourUserIntent = UserIntent.Saetze;
		} else if (m4.find() || m5.find() || m6.find()) {
			ourUserIntent = UserIntent.Abbrechen;
		} else {
			ourUserIntent = UserIntent.Error;
		}
		logger.info("set ourUserIntent to " + ourUserIntent);
	}

	@Override
	public void onSessionEnded(SpeechletRequestEnvelope<SessionEndedRequest> requestEnvelope) {
		logger.info("Alexa session ends now");
	}

	/**
	 * Tell the user something - the Alexa session ends after a 'tell'
	 */
	private SpeechletResponse tellUserAndFinish(String text) {
		// Create the plain text output.
		PlainTextOutputSpeech speech = new PlainTextOutputSpeech();
		speech.setText(text);

		return SpeechletResponse.newTellResponse(speech);
	}

	/**
	 * A response to the original input - the session stays alive after an ask
	 * request was send. have a look on
	 * https://developer.amazon.com/de/docs/custom-skills/speech-synthesis-markup-language-ssml-reference.html
	 * 
	 * @param text
	 * @return
	 */
	private SpeechletResponse askUserResponse(String text) {
		SsmlOutputSpeech speech = new SsmlOutputSpeech();
		speech.setSsml("<speak>" + text + "</speak>");

		// reprompt after 8 seconds
		SsmlOutputSpeech repromptSpeech = new SsmlOutputSpeech();
		repromptSpeech.setSsml("<speak><emphasis level=\"strong\">Hey!</emphasis> Bist du noch da?</speak>");

		Reprompt rep = new Reprompt();
		rep.setOutputSpeech(repromptSpeech);
		return SpeechletResponse.newAskResponse(speech, rep);
	}

}
