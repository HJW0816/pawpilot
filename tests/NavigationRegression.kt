import com.estareng.pawpilot.navigation.*

private class FakeTransport : SearchTransport {
    lateinit var connected: () -> Unit
    lateinit var failed: (Throwable) -> Unit
    lateinit var error: (String) -> Unit
    val searches = mutableListOf<String>()
    var closes = 0
    var searchError: Exception? = null
    var synchronousError: String? = null
    var closeError: Exception? = null
    override fun connect(onConnected: () -> Unit, onFailed: (Throwable) -> Unit, onError: (String) -> Unit) {
        connected = onConnected; failed = onFailed; error = onError
    }
    override fun search(keyword: String) {
        searchError?.let { throw it }; searches += keyword
        synchronousError?.let { error(it) }
    }
    override fun close() { closes++; closeError?.let { throw it } }
}

private class Harness {
    val transports = mutableListOf<FakeTransport>()
    val messages = mutableListOf<Pair<String, Boolean>>()
    lateinit var expire: () -> Unit
    var cancelled = 0
    val search = NavigationSearch(
        { FakeTransport().also { transports += it } },
        { action -> expire = action; CancelTask { cancelled++ } },
        { message, busy -> messages += message to busy },
    )
    init { search.start() }
}

fun main() {
    Harness().apply {
        search.search("휴게소")
        val failed = transports.single()
        failed.synchronousError = "검색 전달 실패"
        failed.closeError = IllegalStateException("already disconnected")
        failed.connected()
        check(messages.last() == "검색 전달 실패" to false)
        check(!search.connecting && failed.closes == 1)
        failed.connected(); failed.error("늦은 오류")
        check(messages.last().first == "검색 전달 실패")
        search.search("다시 검색"); transports.last().connected()
        check(transports.last().searches == listOf("다시 검색"))
        transports.last().closeError = IllegalStateException()
        search.stop()
        check(!search.connecting)
    }
    Harness().apply {
        search.search(" 휴게소 ")
        check(search.connecting && transports.single().searches.isEmpty())
        search.search("중복 클릭")
        check(transports.size == 1)
        transports.single().connected()
        check(transports.single().searches == listOf("휴게소"))
        check(!search.connecting && cancelled == 1)
        check(messages.last().first.contains("요청"))
        transports.single().connected()
        expire()
        check(transports.single().searches.size == 1 && transports.single().closes == 0)
    }
    Harness().apply {
        search.search("휴게소")
        val old = transports.single()
        val oldTimeout = expire
        search.stop()
        old.connected(); old.failed(IllegalStateException()); old.error("늦은 오류"); oldTimeout()
        check(old.closes == 1 && old.searches.isEmpty())
        check(messages.size == 1)
        search.search("화면 밖 요청")
        check(transports.size == 1)
        search.start(); search.search("매송휴게소(목포)")
        old.connected(); oldTimeout()
        transports.last().connected()
        check(transports.last().searches == listOf("매송휴게소(목포)"))
    }
    Harness().apply {
        search.search("휴게소"); expire()
        check(!search.connecting && transports.single().closes == 1)
        transports.single().connected()
        check(transports.single().searches.isEmpty())
        check(messages.last().first.contains("지연"))
        search.search("휴게소"); transports.last().connected()
        check(transports.last().searches.size == 1)
    }
    Harness().apply {
        search.search("휴게소"); transports.single().failed(SecurityException())
        check(messages.last().first.contains("권한") && !search.connecting)
        check(transports.single().closes == 1)
    }
    Harness().apply {
        search.search("휴게소"); transports.single().searchError = SecurityException()
        transports.single().connected()
        check(messages.last().first.contains("권한"))
        check(transports.single().searches.isEmpty() && transports.single().closes == 1)
    }
    Harness().apply {
        search.search(" "); check(transports.isEmpty())
        search.search("휴게소"); transports.single().failed(IllegalStateException())
        check(messages.last().first.contains("연결하지 못"))
        search.search("휴게소"); transports.last().connected()
        transports.last().error("내비 오류")
        check(messages.last() == "내비 오류" to false)
    }
    val facility = PetFacility("놀이터", "매장 옆", "24시간", "연중무휴")
    val stops = groupRestStops(listOf("남한강" to facility, "매송(서울)" to facility,
        "매송(목포)" to facility, "남한강" to facility.copy(location = "옥상", hours = "10:00~18:00")))
    check(stops.size == 3)
    check(stops.first { it.name == "남한강" }.facilities.map { it.hours } == listOf("24시간", "10:00~18:00"))
    check(stops.first { it.name == "매송(목포)" }.searchKeyword == "매송휴게소(목포)")
    check(stops.first { it.name == "남한강" }.searchKeyword == "남한강휴게소")
    println("PASS: navigation connection, duplicate request, late callbacks, timeout/retry, permission/error, lifecycle; facility grouping and directions")
}
