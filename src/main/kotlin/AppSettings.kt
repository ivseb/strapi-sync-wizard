import io.ktor.server.config.ApplicationConfig

object AppSettings {
    lateinit var dbSalt: String
        private set

    fun init(config: ApplicationConfig) {
        if (this::dbSalt.isInitialized) return
        dbSalt = config.propertyOrNull("database.DB_SALT")?.getString()
            ?: System.getenv("DB_SALT")
            ?: "" // fallback compatibile col tuo codice attuale
        println("DB salt: $dbSalt")
    }
}
