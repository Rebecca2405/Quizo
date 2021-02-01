/**
    Copyright 2014-2015 Amazon.com, Inc. or its affiliates. All Rights Reserved.

    Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file except in compliance with the License. A copy of the License is located at

        http://aws.amazon.com/apache2.0/

    or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
 */
package com.amazon.customskill;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;
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

	// Variablen, die wir auch schon in DialogOS hatten
	static int sum;
	static String antwort1 = "";
	static String antwort2 = "";
	static String antwort3 = "";
	static String antwort4 = "";
	static String frage = "";
	static UserIntent correctAnswer = null;

	// Was der User gesagt hat
	public static String userRequest;

	// In welchem Spracherkennerknoten sind wir?
	static enum RecognitionState {
		Anfangsfrage, Quizfrage
	};

	RecognitionState recState;

	// Was hat der User grade gesagt. (Die "Semantic Tags"aus DialogOS)
	static enum UserIntent {
		A, B, C, D, Error, Vokabeln, Saetze, Abbrechen 
	};

	static enum VokabelnOderSaetze {
		Vokabeln, Saetze
	};

	VokabelnOderSaetze status;

	UserIntent ourUserIntent;

	// Was das System sagen kann
	Map<String, String> utterances;

	// Baut die Systemäußerung zusammen
	String buildString(String msg, String replacement1, String replacement2) {
		return msg.replace("{replacement}", replacement1).replace("{replacement2}", replacement2);
	}

	// Liest am Anfang alle Systemäußerungen aus Datei ein
	Map<String, String> readSystemUtterances() {
		Map<String, String> utterances = new HashMap<String, String>();
		try {
			for (String line : IOUtils
					.readLines(this.getClass().getClassLoader().getResourceAsStream("utterances.txt"))) {
				if (line.startsWith("#")) {
					continue;
				}
				String[] parts = line.split("=");
				String key = parts[0].trim();
				String utterance = parts[1].trim();
				utterances.put(key, utterance);
			}
			logger.info("Read " + utterances.keySet().size() + "utterances");
		} catch (IOException e) {
			logger.info("Could not read utterances: " + e.getMessage());
			System.err.println("Could not read utterances: " + e.getMessage());
		}
		return utterances;
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
		utterances = readSystemUtterances();
		sum = 0;
	}

	// Wir starten den Dialog:
	// * Hole die erste Frage aus der Datenbank
	// * Lies die Welcome-Message vor, dann die Frage
	// * Dann wollen wir eine Antwort erkennen
	@Override
	public SpeechletResponse onLaunch(SpeechletRequestEnvelope<LaunchRequest> requestEnvelope) {
		logger.info("onLaunch");
//		selectQuestion();
		recState = RecognitionState.Anfangsfrage;
		return askUserResponse(utterances.get(
				"Schön, dass du da bist. Ich bin dein persönlicher Vokabeltrainer. Du kannst zu Beginn zwischen einzelnen Vokabeln und ganzen Sätzen wählen, die ich dich dann abfrage. Zu jeder Frage erhälst du vier Antwortmöglichkeiten. Du kannst mit den Buchstaben A, B,C, D antworten. Wenn du etwas nicht verstanden hast, frage einfach nach ob ich es wiederholen kann. Sollen wir starten?")
				+ " " + frage);
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
		SpeechletResponse resp = null;
		recognizeUserIntent(userRequest);
		if (ourUserIntent == UserIntent.Abbrechen) {
			return tellUserAndFinish("Bis zum nächsten Mal.");
		}
		switch (recState) {
		case Anfangsfrage:
			resp = beantworteAnfangsfrage(userRequest);
			break;
		case Quizfrage:
			resp = beantworteQuizfrage(userRequest);
			break;
		}
		return resp;
	}



	private SpeechletResponse beantworteQuizfrage(String userRequest) {
		SpeechletResponse res = null;
		if (ourUserIntent == correctAnswer) {
			stelleEineFrage("Super. Das war richtig. ");
		} else {
			stelleEineFrage("Das war leider falsch. ");
		}

		return res;
	}

	private SpeechletResponse beantworteAnfangsfrage(String userRequest) {
		SpeechletResponse response = null;
		
		if (ourUserIntent == UserIntent.Saetze) {
			status = VokabelnOderSaetze.Saetze;
			return stelleEineFrage("");
		}
		if (ourUserIntent == UserIntent.Vokabeln) {
			status = VokabelnOderSaetze.Vokabeln;
			return stelleEineFrage("");
		}
		return response;
	}

	private SpeechletResponse stelleEineFrage(String prefix) {
		SpeechletResponse response = null;
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
			rs.last();
			int anzahl = rs.getRow();
			int randomNum = ThreadLocalRandom.current().nextInt(1, anzahl);
			rs.first();
			while (rs.next()) {
				if (rs.getRow() == randomNum) {
					frage = rs.getString("Frage");
					switch (rs.getString("Richtig")) {
					case "A":
						correctAnswer = UserIntent.A;
						break;
					case "B":
						correctAnswer = UserIntent.B;
						break;
					case "C":
						correctAnswer = UserIntent.C;
						break;
					case "D":
						correctAnswer = UserIntent.D;
						break;
					}
					antwort1 = rs.getString("Antwort 1");
					antwort2 = rs.getString("Antwort 2");
					antwort3 = rs.getString("Antwort 3");
					antwort4 = rs.getString("Antwort 4");
					logger.info("Extracted question from database " + frage);
				}

			}
			return askUserResponse(prefix + "Was beudetet " + frage + " auf Englisch?");
		} catch (Exception e) {
			e.printStackTrace();
		}
		return response;
	}

	// Achtung, Reihenfolge ist wichtig!
	void recognizeUserIntent(String userRequest) {
		userRequest = userRequest.toLowerCase();
		String pattern1 = "(ich nehme )?(antwort )?(\\b[a-d]\\b)( bitte)?";
		String pattern2 = "\bVokabeln\b";
		String pattern3 = "\bSätze\b";
		String pattern4 = "(ich möchte )?(aufhören )";

		Pattern p1 = Pattern.compile(pattern1);
		Matcher m1 = p1.matcher(userRequest);
		Pattern p2 = Pattern.compile(pattern2);
		Matcher m2 = p2.matcher(userRequest);
		Pattern p3 = Pattern.compile(pattern3);
		Matcher m3 = p3.matcher(userRequest);
		Pattern p4 = Pattern.compile(pattern4);
		Matcher m4 = p4.matcher(userRequest);

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
		} else if (m2.find()) {
			ourUserIntent = UserIntent.Vokabeln;
		} else if (m3.find()) {
			ourUserIntent = UserIntent.Saetze;
		} else if (m4.find()) {
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
