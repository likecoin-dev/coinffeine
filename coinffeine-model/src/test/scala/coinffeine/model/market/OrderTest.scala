package coinffeine.model.market

import coinffeine.common.test.UnitTest
import coinffeine.model.bitcoin.test.CoinffeineUnitTestNetwork
import coinffeine.model.bitcoin.{ImmutableTransaction, MutableTransaction}
import coinffeine.model.currency._
import coinffeine.model.exchange._

class OrderTest extends UnitTest with SampleExchange with CoinffeineUnitTestNetwork.Component {

  val exchangeParameters = Exchange.Parameters(10, network)
  val dummyDeposits = Both.fill(ImmutableTransaction(new MutableTransaction(network)))

  "An order" must "report no progress with no exchanges" in {
    Order.random(Bid, 10.BTC, Price(10.EUR)).progress shouldBe 0.0
  }

  it must "report progress with one incomplete exchange" in {
    val order = Order.random(Bid, 10.BTC, Price(10.EUR)).withExchange(createExchangeInProgress(5))
    order.progress shouldBe 0.5
  }

  it must "report progress with one incomplete exchange that overwrites itself" in {
    val exchange = createExchangeInProgress(5)
    val order = Order.random(Bid, 10.BTC, Price(10.EUR))
      .withExchange(exchange)
      .withExchange(exchange.completeStep(6))
    order.progress shouldBe 0.6
  }

  it must "report progress with a mixture of completed and incomplete exchanges" in {
    val order = Order.random(Bid, 20.BTC, Price(10.EUR))
      .withExchange(createSuccessfulExchange())
      .withExchange(createExchangeInProgress(5))
    order.progress shouldBe 0.75
  }

  it must "have its amount pending at the start" in {
    val order = Order.random(Bid, 10.BTC, Price(1.EUR))
    order.amounts shouldBe Order.Amounts(exchanged = 0.BTC, exchanging = 0.BTC, pending = 10.BTC)
  }

  it must "consider successfully exchanged amounts" in {
    val order = Order.random(Bid, 100.BTC, Price(1.EUR))
      .withExchange(createSuccessfulExchange())
      .withExchange(createSuccessfulExchange())
    order.amounts shouldBe Order.Amounts(exchanged = 20.BTC, exchanging = 0.BTC, pending = 80.BTC)
  }

  it must "consider in-progress exchange amounts" in {
    val order = Order.random(Bid, 100.BTC, Price(1.EUR))
      .withExchange(createSuccessfulExchange())
      .withExchange(createExchangeInProgress(5))
    order.amounts shouldBe Order.Amounts(exchanged = 10.BTC, exchanging = 10.BTC, pending = 80.BTC)
  }

  it must "detect completion when exchanges complete the order" in {
    val order = Order.random(Bid, 20.BTC, Price(1.EUR))
      .withExchange(createSuccessfulExchange())
      .withExchange(createSuccessfulExchange())
    order.status shouldBe CompletedOrder
  }

  it must "become offline when the pending amount changes" in {
    val order = Order.random(Bid, 20.BTC, Price(1.EUR))
      .becomeInMarket
      .withExchange(createSuccessfulExchange())
    order should not be 'inMarket
  }

  it must "be in market when there is pending amount to be exchanged" in {
    Order.random(Bid, 10.BTC, Price(1.EUR)) shouldBe 'shouldBeOnMarket
  }

  it must "not be in market when the exchange is finished" in {
    val exchange = Order.random(Bid, 10.BTC, Price(1.EUR))
    exchange.cancel should not be 'shouldBeOnMarket
    exchange.withExchange(createSuccessfulExchange()) should not be 'shouldBeOnMarket
  }

  it must "not be in market while an exchange is running" in {
    Order.random(Bid, 20.BTC, Price(1.EUR)).withExchange(createExchangeInProgress(5)) should
      not be 'shouldBeOnMarket
  }

  private def createSuccessfulExchange() = createExchangeInProgress(10).complete

  private def createExchangeInProgress(stepsCompleted: Int) = {
    createRandomExchange()
      .startHandshaking(participants.buyer, participants.seller)
      .startExchanging(dummyDeposits)
      .completeStep(stepsCompleted)
  }

  private def createRandomExchange(): HandshakingExchange[Euro.type] = {
    buyerExchange.copy(metadata = buyerExchange.metadata.copy(id = ExchangeId.random()))
  }
}
