package ng.checkpoint.ui

/**
 * The languages the app offers.
 *
 * `speechTag` is null for English on purpose. Forcing a tag at the recogniser is how voice
 * broke once already: it has no offline pack for a tag it was not given, and refuses rather
 * than substituting. English here means whichever English this phone already speaks, so the
 * device default is the right answer. A deliberate choice of French or Portuguese is worth
 * naming, and if the pack is missing the error says so.
 */
enum class Lang(val tag: String, val label: String, val speechTag: String?) {
    EN("en", "EN", null),
    FR("fr", "FR", "fr-FR"),
    PT("pt", "PT", "pt-PT");

    companion object {
        fun of(tag: String): Lang = entries.firstOrNull { it.tag == tag } ?: EN
    }
}

/**
 * Everything the app says about itself, as distinct from what the packs say about the law.
 *
 * These are interface words, so they are code. Claim text, rule text and citations are
 * content and live in `packs/`, where a human edits and checks them. Nothing here ever
 * describes what the law is.
 */
data class Copy(
    val placeholder: String,
    val speak: String,
    val listening: String,
    val check: String,
    val reading: String,
    val clear: String,
    val online: String,
    val offline: String,
    val readIn: (Long) -> String,
    val oneMoreThing: String,
    val notSure: String,
    val listen: String,
    val stopReading: String,
    val essentialWhy: String,
    val fullerWhy: String,
    val known: (Int, Int) -> String,
    val skipQuestions: String,
    val asWritten: String,
    val captured: (String) -> String,
    val noCheckedAnswer: String,
    val whatThisDescribes: String,
    val youSaid: String,
    val viaFact: String,
    val related: String,
    val multi: (Int) -> String,
    val nearNote: String,
    val bestScore: (Int) -> String,
    val yes: String,
    val no: String,
    val startingTitle: String,
    val startingBody: String,
    val checkingTitle: String,
    val checkingBody: String,
    val fetchingTitle: String,
    val fetchingBody: String,
    val brokenTitle: String,
    val brokenBody: String,
    val openers: List<String>,
) {
    companion object {
        fun of(lang: Lang): Copy = when (lang) {
            Lang.EN -> EN
            Lang.FR -> FR
            Lang.PT -> PT
        }

        private val EN = Copy(
            placeholder = "Say what the officer told you, in your own words.",
            speak = "Speak", listening = "Listening", check = "Check", reading = "Reading",
            clear = "CLEAR", online = "ONLINE", offline = "OFFLINE",
            readIn = { ms -> "read on this device in $ms ms, with nothing sent anywhere" },
            oneMoreThing = "One more thing",
            notSure = "Not sure",
            listen = "LISTEN",
            stopReading = "STOP",
            essentialWhy = "Without this I cannot say anything useful.",
            fullerWhy = "A fuller picture gives a better answer, so I would rather ask than guess.",
            known = { a, b -> "$a of $b known" },
            skipQuestions = "Skip the questions and show what you have",
            asWritten = "AS WRITTEN",
            captured = { date -> "captured $date" },
            noCheckedAnswer = "No checked answer",
            whatThisDescribes = "WHAT THIS DESCRIBES",
            youSaid = "you said",
            viaFact = "applies because of what you told me",
            related = "related, not your exact question",
            multi = { n -> "$n checked claims apply to what you said." },
            nearNote = "No exact match. These checked claims are the closest. Read them as background, not as an answer to your question.",
            bestScore = { p -> "nothing in this pack is a close enough match to show (best $p%)" },
            yes = "Yes", no = "No",
            startingTitle = "Starting", startingBody = "Loading the checked claims.",
            checkingTitle = "Checking the model",
            checkingBody = "The file is hashed on every start, because a truncated model and a broken app look identical from the inside.",
            fetchingTitle = "Getting the model",
            fetchingBody = "This happens once. After it, the app never needs the network again.",
            brokenTitle = "Cannot start",
            brokenBody = "The encoder has to be on this device before anything can be read. Nothing is guessed in the meantime.",
            openers = listOf(
                "They stopped me and said I must bring 50k",
                "Police say my vehicle particulars are not complete",
                "Officer wants to search my boot",
            ),
        )

        private val FR = Copy(
            placeholder = "Dites ce que l'agent vous a dit, avec vos propres mots.",
            speak = "Parler", listening = "J'écoute", check = "Vérifier", reading = "Lecture",
            clear = "EFFACER", online = "EN LIGNE", offline = "HORS LIGNE",
            readIn = { ms -> "lu sur cet appareil en $ms ms, sans rien envoyer ailleurs" },
            oneMoreThing = "Encore une chose",
            notSure = "Je ne sais pas",
            listen = "ÉCOUTER",
            stopReading = "ARRÊTER",
            essentialWhy = "Sans cela je ne peux rien dire d'utile.",
            fullerWhy = "Une image plus complète donne une meilleure réponse, je préfère demander que deviner.",
            known = { a, b -> "$a sur $b connus" },
            skipQuestions = "Passer les questions et montrer ce que vous avez",
            asWritten = "TEXTE D'ORIGINE",
            captured = { date -> "relevé le $date" },
            noCheckedAnswer = "Aucune réponse vérifiée",
            whatThisDescribes = "CE QUE CELA DÉCRIT",
            youSaid = "vous l'avez dit",
            viaFact = "s'applique d'après ce que vous m'avez dit",
            related = "proche, mais pas votre question exacte",
            multi = { n -> "$n affirmations vérifiées correspondent à ce que vous avez dit." },
            nearNote = "Pas de correspondance exacte. Voici les affirmations vérifiées les plus proches. Lisez-les comme contexte, pas comme une réponse à votre question.",
            bestScore = { p -> "rien dans ce pack n'est assez proche pour être montré (meilleur score $p%)" },
            yes = "Oui", no = "Non",
            startingTitle = "Démarrage", startingBody = "Chargement des affirmations vérifiées.",
            checkingTitle = "Vérification du modèle",
            checkingBody = "Le fichier est vérifié par empreinte à chaque démarrage, car un modèle tronqué et une application cassée se ressemblent de l'intérieur.",
            fetchingTitle = "Téléchargement du modèle",
            fetchingBody = "Cela n'arrive qu'une fois. Ensuite l'application n'a plus jamais besoin du réseau.",
            brokenTitle = "Démarrage impossible",
            brokenBody = "Le modèle doit être sur cet appareil avant toute lecture. Rien n'est deviné en attendant.",
            openers = listOf(
                "Ils m'ont arrêté et disent que je dois apporter 50000",
                "La police dit que mes papiers de véhicule ne sont pas complets",
                "L'agent veut fouiller mon coffre",
            ),
        )

        private val PT = Copy(
            placeholder = "Diga o que o agente falou, com suas próprias palavras.",
            speak = "Falar", listening = "Ouvindo", check = "Verificar", reading = "Lendo",
            clear = "LIMPAR", online = "ONLINE", offline = "OFFLINE",
            readIn = { ms -> "lido neste aparelho em $ms ms, sem enviar nada para lugar nenhum" },
            oneMoreThing = "Mais uma coisa",
            notSure = "Não sei",
            listen = "OUVIR",
            stopReading = "PARAR",
            essentialWhy = "Sem isso não consigo dizer nada de útil.",
            fullerWhy = "Um quadro mais completo dá uma resposta melhor, então prefiro perguntar a adivinhar.",
            known = { a, b -> "$a de $b conhecidos" },
            skipQuestions = "Pular as perguntas e mostrar o que você tem",
            asWritten = "TEXTO ORIGINAL",
            captured = { date -> "registrado em $date" },
            noCheckedAnswer = "Nenhuma resposta verificada",
            whatThisDescribes = "O QUE ISSO DESCREVE",
            youSaid = "você disse",
            viaFact = "se aplica pelo que você me contou",
            related = "próximo, mas não é a sua pergunta exata",
            multi = { n -> "$n afirmações verificadas se aplicam ao que você disse." },
            nearNote = "Sem correspondência exata. Estas são as afirmações verificadas mais próximas. Leia como contexto, não como resposta à sua pergunta.",
            bestScore = { p -> "nada neste pacote está próximo o bastante para mostrar (melhor $p%)" },
            yes = "Sim", no = "Não",
            startingTitle = "Iniciando", startingBody = "Carregando as afirmações verificadas.",
            checkingTitle = "Verificando o modelo",
            checkingBody = "O arquivo é conferido por hash a cada início, porque um modelo truncado e um aplicativo quebrado são idênticos por dentro.",
            fetchingTitle = "Baixando o modelo",
            fetchingBody = "Isso acontece uma vez só. Depois disso o aplicativo nunca mais precisa da rede.",
            brokenTitle = "Não foi possível iniciar",
            brokenBody = "O modelo precisa estar neste aparelho antes de qualquer leitura. Nada é adivinhado nesse meio tempo.",
            openers = listOf(
                "Me pararam e disseram que tenho que trazer 50 mil",
                "A polícia diz que meus documentos do veículo não estão completos",
                "O agente quer revistar meu porta-malas",
            ),
        )
    }
}
