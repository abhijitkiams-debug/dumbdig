package com.originalgames.astra

import kotlin.random.Random

/** A single Ramayana knowledge question — the "Gyaan Dwar" gate before a battle. */
data class Question(val text: String, val options: List<String>, val answer: Int)

object Quiz {
    val BANK: List<Question> = listOf(
        Question("Who is the devoted vanara that serves Ram?",
            listOf("Hanuman", "Sugriva", "Jambavan", "Angad"), 0),
        Question("What is the name of Ram's bow?",
            listOf("Gandiva", "Sharanga", "Pinaka", "Vijaya"), 1),
        Question("Ravan was the king of which land?",
            listOf("Kishkindha", "Ayodhya", "Lanka", "Mithila"), 2),
        Question("Who is Ram's ever-loyal younger brother in exile?",
            listOf("Bharat", "Shatrughna", "Lakshman", "Sugriva"), 2),
        Question("Ram is an avatar of which god?",
            listOf("Shiva", "Vishnu", "Brahma", "Indra"), 1),
        Question("Who is Ram's wife, abducted by Ravan?",
            listOf("Sita", "Urmila", "Mandodari", "Kaikeyi"), 0),
        Question("Which astra is the ultimate weapon of Brahma?",
            listOf("Agneyastra", "Nagastra", "Brahmastra", "Vayavyastra"), 2),
        Question("Who built the bridge (Setu) to Lanka?",
            listOf("The devas", "Ram's vanara army", "Vishwakarma alone", "Ravan"), 1),
        Question("Ravan's mighty son and sorcerer was named?",
            listOf("Kumbhakarna", "Meghnad (Indrajit)", "Vibhishana", "Vali"), 1),
        Question("How many heads did Ravan have?",
            listOf("Seven", "Nine", "Ten", "Twelve"), 2),
        Question("Who carried the Sanjeevani mountain to heal Lakshman?",
            listOf("Hanuman", "Sugriva", "Jambavan", "Nala"), 0),
        Question("In which forest did Ram, Sita and Lakshman spend exile?",
            listOf("Naimisha", "Dandaka", "Vrindavan", "Khandava"), 1)
    )

    fun random(rng: Random): Question = BANK[rng.nextInt(BANK.size)]
}
