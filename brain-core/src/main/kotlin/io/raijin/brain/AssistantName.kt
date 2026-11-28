package io.raijin.brain

/**
 * The assistant's own name: parsing "call yourself Eva", the wake phrase "hey <name>", and a small
 * built-in table that maps common names to a voice gender. Everything is local; no network.
 */
object AssistantName {
    const val DEFAULT = "Raijin"
    const val MAX_LEN = 20

    private val RENAME = Regex(
        """^(?:please\s+)?(?:(?:from\s+now\s+on\s+)?(?:call\s+yourself|your\s+name\s+is(?:\s+now)?|rename\s+yourself(?:\s+to)?|change\s+your\s+name\s+to|i(?:'ll|\s+will)\s+call\s+you|you(?:'re|\s+are)\s+(?:now\s+)?called))\s+([\p{L}][\p{L}'\-]*(?:\s+(?!(?:female|male|woman|man|girl|boy|she|he)\b)[\p{L}][\p{L}'\-]*)?)(?:\s*,?\s*(?:and\s+)?(?:(?:she|he)\s+is|you(?:'re|\s+are)\s+(?:a\s+)?|use\s+(?:a\s+)?)?(female|male|woman|man|girl|boy|she|he)(?:\s+voice)?)?$""",
        RegexOption.IGNORE_CASE,
    )

    data class Rename(val name: String, val male: Boolean?)

    fun parseRename(text: String): Rename? {
        val t = text.trim().trimEnd('.', '!', '?').trim()
        val m = RENAME.matchEntire(t) ?: return null
        val name = clean(m.groupValues[1]) ?: return null
        val g = m.groupValues[2].lowercase()
        val male = when (g) {
            "" -> null
            "male", "man", "boy", "he" -> true
            else -> false
        }
        return Rename(name, male)
    }

    /** Bare answer to "male or female?". */
    fun parseGenderAnswer(text: String): Boolean? {
        val t = text.trim().trimEnd('.', '!', '?').lowercase()
        return when (t) {
            "male", "a male", "man", "a man", "he", "boy", "a boy", "male voice", "a male name", "it's male", "it is male" -> true
            "female", "a female", "woman", "a woman", "she", "girl", "a girl", "female voice", "a female name", "it's female", "it is female" -> false
            else -> null
        }
    }

    fun clean(raw: String): String? {
        val n = raw.trim().split(Regex("\\s+")).joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
        if (n.isEmpty() || n.length > MAX_LEN) return null
        if (n.lowercase() in BLOCKED) return null
        return n
    }

    private val BLOCKED = setOf("me", "you", "it", "yourself", "that", "this", "now", "sir", "madam", "stop", "cancel")

    fun wakePhrase(name: String): String = "hey ${name.trim().lowercase()}"

    /** true = male, false = female, null = not in the table (ask the owner). */
    fun genderOf(name: String): Boolean? {
        val key = name.trim().lowercase().substringBefore(' ')
        return when (key) {
            in FEMALE -> false
            in MALE -> true
            else -> null
        }
    }

    private val FEMALE = setOf(
        "eva", "eve", "ava", "emma", "olivia", "sophia", "isabella", "mia", "amelia", "emily", "grace", "lily", "chloe", "zoe",
        "alice", "anna", "sara", "sarah", "lisa", "julia", "maria", "nina", "luna", "stella", "ella", "hannah", "jessica",
        "jennifer", "mary", "linda", "susan", "karen", "nancy", "laura", "alexa", "siri", "cortana", "jane", "kate", "katie",
        "priya", "anjali", "pooja", "puja", "neha", "kavita", "sunita", "anita", "sita", "geeta", "gita", "radha", "lakshmi",
        "meera", "mira", "riya", "diya", "isha", "kiara", "ananya", "aditi", "shreya", "divya", "sneha", "swati", "nisha",
        "komal", "simran", "preeti", "pooja", "rekha", "sushma", "seema", "sapna", "tanvi", "tara", "uma", "durga", "kali",
        "saraswati", "parvati", "ishita", "aarti", "arti", "deepika", "kajal", "khushi", "mansi", "payal", "ritu", "shweta",
        "jyoti", "madhuri", "namita", "pallavi", "rani", "roshni", "sakshi", "sonia", "sonam", "vidya", "zara", "fatima",
        "ayesha", "aisha", "noor", "sana", "zoya", "maya", "veda", "ira", "anaya", "myra", "aarohi", "navya", "kriti",
    )
    private val MALE = setOf(
        "george", "james", "john", "robert", "michael", "william", "david", "richard", "joseph", "thomas", "charles", "daniel",
        "matthew", "mark", "paul", "steve", "peter", "henry", "jack", "jarvis", "max", "leo", "sam", "alex", "adam", "ben",
        "tom", "tim", "jake", "luke", "noah", "liam", "ethan", "oliver", "arthur", "edward", "frank", "oscar", "felix",
        "raj", "rahul", "rohit", "amit", "ajay", "vijay", "sanjay", "sandeep", "deepak", "ankit", "arjun", "karan", "vikram",
        "vivek", "ravi", "ramesh", "suresh", "mahesh", "mohan", "krishna", "krish", "shyam", "ram", "hari", "gopal", "govind",
        "aryan", "rohan", "nikhil", "manish", "mukesh", "naveen", "prakash", "pranav", "rajesh", "sachin", "sunil", "tarun",
        "varun", "yash", "kartik", "kunal", "harsh", "dev", "ishaan", "aarav", "vihaan", "reyansh", "atharv", "shiv", "shiva",
        "ganesh", "hanuman", "thor", "zeus", "raijin", "ali", "omar", "imran", "faisal", "zain", "rehan", "ayaan",
    )
}
