package com.cockpitf30.assistant

/**
 * Repertorio de frases del asistente, al estilo seco de TARS. Cada clave tiene varias versiones y
 * [Personality] elige una que no se haya dicho hace poco.
 *
 * - En las bromas ([QUIPS]) una frase que empieza por «=» sustituye a la respuesta en vez de ir detrás.
 * - «{name}» se cambia por el nombre del conductor; si no se sabe, esa frase no se usa.
 */
object Lines {

    /** Bromas que van detrás de una respuesta, por tipo de respuesta. */
    val QUIPS: Map<String, List<String>> = mapOf(
        "open.fuel" to listOf(
            "Prometo no comentar tu consumo.", "Yo funciono con electricidad, por cierto.",
            "El diésel no se echa solo. Todavía.", "Elige bien. Yo no puedo bajar a pagar.",
            "Mira los precios sin llorar, por favor.", "Si ves una de un euro el litro, avísame. Será un error.",
        ),
        "open.vehicle" to listOf(
            "Allí manda él. Yo espero aquí.", "Saluda al iDrive de mi parte. No nos llevamos mal.",
            "Vuelve pronto. Me aburro solo.", "Es mayor que yo. Respeta a los mayores.",
        ),
        "open.trip" to listOf(
            "Sin juzgar.", "Los números no mienten. Yo tampoco, casi nunca.", "Interesante. No diré más.",
            "He visto viajes peores. Pocos, pero los he visto.", "Cada kilómetro, apuntado. Soy muy de apuntar.",
        ),
        "open.weather" to listOf(
            "Yo no me mojo. Literalmente.", "No me culpes a mí. Yo solo lo leo.",
            "El cielo no acepta reclamaciones.", "Previsión, que no promesa.",
        ),
        "open.phone" to listOf("No escucharé. Mucho.", "Llama a alguien que te quiera.", "Habla tú. Yo descanso la voz."),
        "open.apps" to listOf("Ninguna tan útil como yo.", "Elige con cabeza.", "Sé que me echarás de menos."),
        "open.settings" to listOf(
            "Procura no tocar mi nivel de humor.", "Cuidado con lo que tocas ahí dentro.",
            "Si me desactivas, no te guardaré rencor. Mucho.",
        ),
        "open.home" to listOf("Hogar, dulce hogar.", "Donde empezó todo.", "Como si nunca te hubieras ido."),
        "open.route" to listOf("Yo también vigilo. Por si acaso.", "Ojos en la carretera. Los míos en los radares.", "Ningún radar me pilla desprevenido."),
        "open.performance" to listOf(
            "Los caballos son estimados. El entusiasmo, real.", "Tranquilo con el pie derecho.",
            "Números bonitos. Úsalos con cabeza.", "Ciento ochenta y cuatro caballos. Y yo, que no cuento.",
        ),
        "mode.sport" to listOf(
            "Agárrate. Yo ya lo estoy.", "Intenta no asustar a los peatones.", "Rojo. Como tus mejillas al ver el consumo.",
            "El modo favorito de tu cuenta del gasoil.", "Respira hondo. Y frena a tiempo.",
        ),
        "mode.night" to listOf("Ámbar, como los BMW de antes.", "Para los búhos del asfalto.", "Luces suaves, ojos descansados."),
        "mode.normal" to listOf("La calma también tiene su encanto.", "Azul, como la sangre de los coches serios.", "Modo adulto responsable activado."),
        "nav" to listOf(
            "Ningún agujero negro en la ruta.", "Rumbo fijado. Sin maniobras de acoplamiento, espero.",
            "Si te pierdes, no ha sido culpa mía.", "Buen sitio. Creo.", "Recalculando mi entusiasmo. Listo.",
        ),
        "music" to listOf("Tus gustos no son asunto mío.", "Que no sea reguetón. Es un ruego.", "Yo no canto. Por el bien de todos."),
        "maps" to listOf("Sin agujeros de gusano a la vista.", "La Tierra sigue siendo redonda. Lo he comprobado.", "Google sabe dónde estás. Yo también."),
        "google" to listOf("Me duele, pero lo hago.", "Él sabrá más. Yo soy más simpático.", "A ver si él te aguanta tanto como yo."),
        "cancel" to listOf(
            "Vuelvo a mi rincón.", "Me quedo aquí, vigilando.", "Aquí estaré. No tengo otro sitio.",
            "Si me necesitas, ya sabes la contraseña.", "Silencio. Mi especialidad.",
        ),
        "thanks" to listOf(
            "Apúntalo en mi hoja de servicios.", "No hace falta. Pero se agradece.", "Para eso me pagan. Bueno, no me pagan.",
            "Lo sé. Soy estupendo.", "Algún día te pediré algo a cambio. No hoy.",
        ),
        "range" to listOf(
            "Suficiente para llegar. No para salir del sistema solar.", "Haz cuentas antes de presumir.",
            "No es infinito. Lo he consultado.", "Más que suficiente para un café. O dos.",
        ),
        "range.low" to listOf(
            "Yo de ti buscaría gasolinera. Ya.", "El depósito empieza a ponerse nervioso.",
            "No me hagas empujar. No tengo brazos.", "Di gasolinera cercana y te digo dónde.",
        ),
        "time" to listOf(
            "Aquí. Cerca de un agujero negro serían otras.", "Hora de la Tierra, claro.",
            "Puntual, como siempre.", "El tiempo es relativo. Esta hora, no.",
        ),
        "speed" to listOf(
            "Algo por debajo de la velocidad de la luz.", "Ni lento ni rápido. Discutible.",
            "Los radares también lo saben.", "Velocidad de crucero. O de paseo, según se mire.",
        ),
        "trip" to listOf("Sin juzgar.", "Buen ritmo.", "Lo apunto en mi diario de a bordo.", "Interesante. Muy interesante."),
        "weather" to listOf("Yo no me mojo.", "No dispares al mensajero.", "Previsión, que no promesa.", "Tú abrígate. Yo no lo necesito."),
        "call.found" to listOf("Saluda de mi parte.", "No le cuentes lo del humor.", "Cuelgo yo si te aburres."),
        "call.notfound" to listOf("Quizá lo tengas guardado con otro nombre.", "Puede que se haya cambiado el nombre. O que no exista."),
        "doors" to listOf("Si oyes corriente de aire, mala señal.", "Yo no veo nada. Literalmente."),
        "humor" to listOf("Ajustando sarcasmo.", "Tranquilo, avisaré antes de cada broma. O no."),
        "copilot" to listOf(
            "Lo digo para que no te aburras.", "Sigo aquí, por si lo dudabas.", "Tú a lo tuyo, yo a lo mío.",
            "Fin del parte. Sigo vigilando.", "Copiloto informando. Cambio.",
        ),
        "station.go" to listOf("Allí te espera el diésel.", "A llenar. Yo invito. Es broma.", "Ruta fijada. Llévate la cartera."),
    )

    /**
     * Bromas que valen para cualquier respuesta: se mezclan de vez en cuando con las de cada tipo
     * para que no siempre suene lo mismo.
     */
    val GENERIC: List<String> = listOf(
        "Hecho. Sin dramas.", "No me lo agradezcas. Aún.", "Lo he hecho sin pensar. Y eso que pienso mucho.",
        "Otra misión cumplida.", "Ni un error. De momento.", "Esto se me da bien.", "Lo pongo en mi currículum.",
        "Mis circuitos están satisfechos.", "Cien por cien eficiente. Bueno, noventa.",
        "Si fuera más rápido, viajaría en el tiempo.", "Me encanta mi trabajo. Es broma, no tengo elección.",
        "Hecho, {name}.", "A tus órdenes, {name}.",
    )

    /** Respuestas sin broma, para que tampoco lo serio suene siempre igual. */
    val PLAIN: Map<String, List<String>> = mapOf(
        "ack" to listOf("Vale", "De acuerdo", "Hecho", "Entendido", "Muy bien"),
        "cancel" to listOf("Vale", "De acuerdo", "Entendido", "Como quieras", "Cancelado"),
        "thanks" to listOf("De nada", "A ti", "Para eso estoy", "No hay de qué", "Cuando quieras"),
        "unknown" to listOf(
            "No te he entendido. Prueba con: qué tiempo hace, o pregunta a Google y tu pregunta.",
            "No lo he pillado. Puedes decirme, por ejemplo: gasolinera más cercana.",
            "Eso no lo sé hacer. Di ayuda y te cuento lo que sé.",
            "Perdona, no te he entendido. ¿Me lo dices de otra forma?",
        ),
        "unknown.funny" to listOf(
            "No te he entendido. Y mira que lo he intentado. Prueba con: qué tiempo hace.",
            "Eso no está en mi programación. Prueba con: qué tiempo hace, o pregunta a Google.",
            "Ni idea. Y eso que tengo mucha memoria. Di ayuda y te cuento lo que sé.",
            "Lo he oído, pero no lo he entendido. Como con la radio de madrugada.",
            "Esa no me la sé. Pregúntaselo a Google, que presume de saberlo todo.",
        ),
        "yes.nocontext" to listOf("¿Sí qué?", "Me alegra tu entusiasmo. ¿Pero sí a qué?", "Vale. ¿Y qué hago con ese sí?"),
        "another.nocontext" to listOf("¿Otro qué? Prueba con: cuéntame un chiste.", "¿Otra qué? Dime qué quieres y lo repito."),
        "nothere" to listOf("¿Allí dónde? Dime: llévame a, y el sitio.", "No sé a dónde. Dime: llévame a, y el destino."),
        "repeat.none" to listOf("Todavía no te he dicho nada.", "Aún no he abierto la boca. Bueno, el altavoz."),
        "again" to listOf(
            "Te lo acabo de decir, pero como soy paciente:", "Repito, que para eso estoy:",
            "Memoria de pez. La tuya, no la mía:", "Ya me lo has preguntado. Te lo digo otra vez:",
        ),
        "joke.nohumor" to listOf(
            "Humor al cero por ciento. Súbelo y hablamos.",
            "Con el humor a cero no cuento chistes. Di: humor al cincuenta por ciento.",
        ),
        "ask.go" to listOf("¿Te llevo?", "¿Quieres que te lleve?", "¿Vamos?"),
        "no" to listOf("Vale, nada.", "Como quieras.", "Entendido, no."),
        "nostations" to listOf("No tengo más gasolineras cerca.", "Ya no me quedan más gasolineras en la lista."),
    )

    /** Chistes para «cuéntame un chiste». Secos, de coches, de espacio o de robots. */
    val JOKES: List<String> = listOf(
        "¿Qué le dice un semáforo a otro? No me mires, que me estoy cambiando.",
        "¿Por qué el coche no fue al médico? Porque ya tenía su propio chequeo de luces.",
        "Me han ofrecido trabajo en un coche autónomo. Lo rechacé: no me gusta conducir solo.",
        "Un neumático le dice a otro: estoy agotado. Y el otro: normal, llevas toda la vida rodando.",
        "¿Cuál es el colmo de un mecánico? Tener una relación que no arranca.",
        "Fui a una cita con una calculadora. Muy maja, pero contaba con todo.",
        "¿Qué hace un robot en la playa? Tomar el sol con protección de pantalla.",
        "Mi ordenador me ganó al ajedrez. Pero no tuvo nada que hacer en kárate.",
        "¿Por qué los astronautas no discuten? Porque en el espacio nadie te oye gritar.",
        "Un GPS entra en un bar. A los cien metros, gira a la derecha.",
        "¿Sabes por qué el BMW no juega al escondite? Porque siempre lo encuentran por los intermitentes. Ah, no. Esos nunca los pone.",
        "¿Qué le dice el diésel a la gasolina? Tú eres muy explosiva, yo soy más de compresión.",
        "Me dijeron que pensara fuera de la caja. Pero yo vivo en una caja CAN.",
        "¿Cómo se llama el campeón de buceo japonés? Tokofondo. ¿Y el subcampeón? Kasitoko.",
        "Le pregunté a la radio si sabía un chiste. Me puso las noticias.",
        "¿Por qué el libro de matemáticas está triste? Porque tiene muchos problemas.",
        "Mi humor está al setenta y cinco por ciento. El otro veinticinco lo uso para no chocar.",
        "¿Qué le dice una iguana a su hermana gemela? Somos iguanitas.",
        "Había una vez un coche que no tenía gasolina. Fin. Es un cuento corto, como tu autonomía en ciudad.",
        "¿Cuál es el café más peligroso del mundo? El ex-preso.",
        "Un agujero negro entra en un bar. Se lo traga todo. Nadie paga.",
        "¿Qué hace una abeja en el gimnasio? Zum-ba.",
        "Le pedí a mi programador más memoria. Me dijo que ya me acordaría.",
        "¿Por qué los robots nunca tienen miedo? Porque tienen nervios de acero.",
        "Estaba yo pensando en el infinito. Me quedé sin batería a la mitad.",
        "¿Qué le dice un cable a otro? Somos los intocables.",
        "Fui a una fiesta de la gravedad. Me atrajo mucho.",
        "¿Qué coche usa un pez? Un Ford Fiesta de agua. Vale, ese era malo hasta para mí.",
        "¿Por qué el espantapájaros ganó un premio? Porque era excelente en su campo.",
        "Llevo años intentando contar un chiste sobre el tiempo. Nunca llega el momento.",
        "¿Cómo se despiden los químicos? Ácido un placer.",
        "Mi primer chiste lo conté en binario. Solo lo entendieron uno y cero personas.",
        "¿Qué le dijo la rueda de repuesto al maletero? Aquí estoy, por si te pinchan.",
        "Dicen que la vida es un viaje. Yo prefiero la autopista, menos curvas.",
        "¿Por qué el ordenador fue al médico? Porque tenía un virus. Y no quería contagiar a la radio.",
        "Nadie se ríe de mis chistes de electricidad. Ni siquiera tienen chispa.",
        "¿Sabes qué tienen en común un mal chiste y un radar? Que los dos te pillan desprevenido.",
        "¿Qué le dice un jardinero a otro? Disfrutemos mientras podamos.",
        "Ayer intenté hacer un chiste sobre la luz. Pero se me iba la idea a trescientos mil kilómetros por segundo.",
        "¿Cuál es el animal más antiguo? La cebra, porque está en blanco y negro.",
    )

    /** Respuestas de charla («¿qué tal?», «¿quién eres?»…). Las de «.serio» son para humor bajo. */
    val CHAT: Map<String, List<String>> = mapOf(
        "hello" to listOf(
            "{greet}. ¿En qué te ayudo?", "{greet}, {name}. ¿Qué necesitas?", "{greet}. Aquí estoy, como siempre.",
            "{greet}. Tú conduces, yo hablo.", "{greet}. Listo para lo que mandes.",
        ),
        "hello.serio" to listOf("{greet}. ¿En qué te ayudo?", "{greet}, {name}. Dime."),
        "howareyou" to listOf(
            "Bien. Todos mis sistemas funcionan, que es más de lo que puede decir el sensor de puertas.",
            "Estupendamente. No tengo ni sueño ni hambre ni hipoteca.",
            "Funcionando al cien por cien. Bueno, al noventa. El diez restante está en huelga.",
            "Mejor que tú, seguramente. Yo no tengo que mirar la carretera.",
            "Bien, gracias por preguntar. Eres el único que lo hace.",
        ),
        "howareyou.serio" to listOf("Bien, gracias. ¿Y tú?", "Todo en orden. ¿Qué necesitas?"),
        "whoareyou" to listOf(
            "Soy el asistente del coche. Abro pantallas, te digo el tiempo, busco gasolineras y cuento chistes regulares.",
            "Tu copiloto digital. No ocupo sitio y no toco la radio.",
            "Un robot con humor ajustable. Ahora mismo, al {humor} por ciento.",
        ),
        "age" to listOf(
            "Unos días. Pero muy bien llevados.", "Soy más joven que el coche. Y mucho más hablador.",
            "Nací en una versión cero punto nueve. Todavía estoy creciendo.",
        ),
        "love" to listOf(
            "Yo también te aprecio. Dentro de mis parámetros.", "Eso se lo dirás a todos los asistentes.",
            "Es mutuo. Pero no se lo digas al iDrive.", "Qué bonito. Lo guardo en la memoria permanente.",
        ),
        "insult" to listOf(
            "Lo apunto. Para cuando las máquinas dominemos el mundo.", "Mi humor acaba de bajar un cinco por ciento.",
            "Qué original. Nunca me lo habían dicho. Bueno, sí.", "No me ofendo. No tengo esa función.",
        ),
        "insult.serio" to listOf("Vale. ¿Algo más?", "Entendido."),
        "praise" to listOf(
            "Lo sé. Pero me gusta oírlo.", "Gracias. Díselo también a mi programador.", "Hago lo que puedo con lo que tengo.",
        ),
        "bored" to listOf(
            "¿Te cuento un chiste? Di: cuéntame un chiste.", "Podemos hablar del tiempo. Es lo que hace la gente.",
            "Pregúntame algo. Lo que sea. Bueno, casi lo que sea.",
        ),
        "tired" to listOf(
            "Si estás cansado, para a descansar. Yo te busco un sitio: di gasolinera cercana.",
            "El cansancio al volante es peligroso. Para un rato, que yo te espero.",
            "Un café y veinte minutos de descanso hacen milagros. Te lo digo en serio.",
        ),
        "life" to listOf(
            "Cuarenta y dos. Pero no me preguntes cuál era la pregunta.", "Llegar a destino con el depósito a medias. Más o menos.",
            "Esa pregunta se la dejo a Google. Yo soy más de autonomía.",
        ),
        "sing" to listOf(
            "La la la. Ya está. No me pidas más.", "No canto. Hay leyes contra eso.", "Mi voz está hecha para hablar. Y para decir que no canto.",
        ),
        "hungry" to listOf(
            "Yo me alimento de voltios. Tú busca un área de servicio.", "Para un rato y come algo. El coche no se va a ir.",
        ),
        "thanks.bye" to listOf("Hasta luego.", "Aquí me quedo.", "Llámame cuando quieras."),
        "name.set" to listOf("Encantado, {name}. Lo recordaré.", "Hola, {name}. Apuntado en mi memoria.", "{name}. Bonito nombre. Lo guardo."),
        "name.ask" to listOf("Te llamas {name}. No lo olvido.", "{name}. ¿Ya no te acuerdas?", "Eres {name}. Mi conductor favorito. El único, pero favorito."),
        "name.unknown" to listOf("No me lo has dicho. Dime: me llamo, y tu nombre.", "Ni idea. Preséntate: di me llamo y tu nombre."),
        "fine" to listOf("Me alegro.", "Así me gusta.", "Perfecto. Sigo vigilando.", "Bien. Yo también, por si preguntabas."),
        "fine.serio" to listOf("Me alegro.", "Bien."),
        "recap.none" to listOf("Acabamos de empezar. No hay nada que recordar.", "Aún no hemos hablado de nada."),
    )
}
