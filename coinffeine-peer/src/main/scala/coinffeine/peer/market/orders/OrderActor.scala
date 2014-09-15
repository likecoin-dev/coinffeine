package coinffeine.peer.market.orders

import scala.concurrent.Future
import scala.util.{Failure, Success}

import akka.actor._
import com.google.bitcoin.core.NetworkParameters

import coinffeine.common.akka.AskPattern
import coinffeine.model.bitcoin.KeyPair
import coinffeine.model.currency.FiatCurrency
import coinffeine.model.event.{OrderProgressedEvent, OrderStatusChangedEvent, OrderSubmittedEvent}
import coinffeine.model.exchange._
import coinffeine.model.market._
import coinffeine.model.network.BrokerId
import coinffeine.model.payment.PaymentProcessor.AccountId
import coinffeine.peer.amounts.AmountsCalculator
import coinffeine.peer.bitcoin.WalletActor
import coinffeine.peer.event.EventPublisher
import coinffeine.peer.exchange.ExchangeActor
import coinffeine.peer.exchange.ExchangeActor.{ExchangeActorProps, ExchangeToStart}
import coinffeine.peer.market.SubmissionSupervisor.KeepSubmitting
import coinffeine.peer.market.orders.controller._
import coinffeine.peer.payment.PaymentProcessorActor
import coinffeine.protocol.gateway.MessageGateway
import coinffeine.protocol.gateway.MessageGateway.{ForwardMessage, ReceiveMessage}
import coinffeine.protocol.messages.brokerage.OrderMatch
import coinffeine.protocol.messages.handshake.ExchangeRejection

class OrderActor[C <: FiatCurrency](initialOrder: Order[C],
                                    controllerFactory: OrderPublication[C] => OrderController[C],
                                    delegates: OrderActor.Delegates[C],
                                    collaborators: OrderActor.Collaborators)
  extends Actor with ActorLogging with EventPublisher {

  import OrderActor._
  import context.dispatcher

  private val publisher = new DelegatedPublication(
    OrderBookEntry(initialOrder), collaborators.submissionSupervisor)
  private val order = controllerFactory(publisher)
  private val fundsActor = context.actorOf(delegates.orderFundsActor, "funds")

  override def preStart(): Unit = {
    log.info("Order actor initialized for {}", order.id)
    subscribeToOrderMatches()
    subscribeToOrderChanges()
    blockFunds()
    publishEvent(OrderSubmittedEvent(order.view))
  }

  override def receive = publisher.receiveSubmissionEvents orElse {
    case RetrieveStatus =>
      log.debug("Order actor requested to retrieve status for {}", order.id)
      sender() ! order.view

    case ReceiveMessage(orderMatch: OrderMatch, _) =>
      order.acceptOrderMatch(orderMatch) match {
        case MatchAccepted(newExchange) => startExchange(newExchange)
        case MatchRejected(cause) => rejectOrderMatch(cause, orderMatch)
        case MatchAlreadyAccepted(oldExchange) =>
          log.debug("Received order match for the already accepted exchange {}", oldExchange)
      }

    case OrderFundsActor.AvailableFunds(availableBlockedFunds) =>
      order.fundsBecomeAvailable(availableBlockedFunds)

    case OrderFundsActor.UnavailableFunds =>
      order.fundsBecomeUnavailable()

    case CancelOrder(reason) =>
      log.info("Cancelling order {}", order.id)
      order.cancel(reason)

    case ExchangeActor.ExchangeProgress(exchange: AnyStateExchange[C]) =>
      log.debug("Order actor received progress for {}: {}", exchange.id, exchange.progress)
      order.updateExchange(exchange)

    case ExchangeActor.ExchangeSuccess(exchange: CompletedExchange[C]) =>
      order.completeExchange(Success(exchange))

    case ExchangeActor.ExchangeFailure(cause) =>
      order.completeExchange(Failure(cause))
  }

  private def subscribeToOrderMatches(): Unit = {
    collaborators.gateway ! MessageGateway.Subscribe.fromBroker {
      case orderMatch: OrderMatch if orderMatch.orderId == order.id &&
        orderMatch.fiatAmount.currency == order.view.price.currency =>
    }
  }

  private def subscribeToOrderChanges(): Unit = {
    order.addListener(new OrderController.Listener[C] {
      override def onProgress(prevProgress: Double, newProgress: Double): Unit = {
        publishEvent(OrderProgressedEvent(order.id, prevProgress, newProgress))
      }

      override def onStatusChanged(oldStatus: OrderStatus, newStatus: OrderStatus): Unit = {
        log.info("Order {} status changed from {} to {}", order.id, oldStatus, newStatus)
        publishEvent(OrderStatusChangedEvent(order.id, oldStatus, newStatus))
      }

      override def onFinish(finalStatus: OrderStatus): Unit = {
        fundsActor ! OrderFundsActor.UnblockFunds
      }
    })
  }

  private def blockFunds(): Unit = {
    val (bitcoinAmount, fiatAmount) = order.requiredFunds
    log.info("{} is stalled until enough funds are available {}", order.id,
      (fiatAmount, bitcoinAmount))
    fundsActor ! OrderFundsActor.BlockFunds(fiatAmount, bitcoinAmount)
  }

  private def rejectOrderMatch(cause: String, rejectedMatch: OrderMatch): Unit = {
    log.info("Rejecting match for {} against counterpart {}: {}",
      order.id, rejectedMatch.counterpart, cause)
    val rejection = ExchangeRejection(rejectedMatch.exchangeId, cause)
    collaborators.gateway ! ForwardMessage(rejection, BrokerId)
  }

  private def startExchange(newExchange: NonStartedExchange[C]): Unit = {
    log.info("Accepting match for {} against counterpart {} identified as {}",
      order.id, newExchange.counterpartId, newExchange.id)
    val userInfoFuture = for {
      keyPair <- createFreshKeyPair()
      paymentProcessorId <- retrievePaymentProcessorId()
    } yield Exchange.PeerInfo(paymentProcessorId, keyPair)
    userInfoFuture.onComplete {
      case Success(userInfo) => spawnExchange(newExchange, userInfo)
      case Failure(cause) =>
        log.error(cause, "Cannot start exchange {} for {} order", newExchange.id, order.id)
        // TODO: mark exchange/order as failed
        collaborators.submissionSupervisor ! KeepSubmitting(OrderBookEntry(order.view))
    }
  }

  private def spawnExchange(exchange: NonStartedExchange[C], user: Exchange.PeerInfo): Unit = {
    val props = delegates.exchangeActor(
      ExchangeActor.ExchangeToStart(exchange, user), resultListener = self)
    context.actorOf(props, exchange.id.value)
  }

  private def createFreshKeyPair(): Future[KeyPair] = AskPattern(
    to = collaborators.wallet,
    request = WalletActor.CreateKeyPair,
    errorMessage = "Cannot get a fresh key pair"
  ).withImmediateReply[WalletActor.KeyPairCreated]().map(_.keyPair)

  private def retrievePaymentProcessorId(): Future[AccountId] = AskPattern(
    to = collaborators.paymentProcessor,
    request = PaymentProcessorActor.RetrieveAccountId,
    errorMessage = "Cannot retrieve the user account id"
  ).withImmediateReply[PaymentProcessorActor.RetrievedAccountId]().map(_.id)
}

object OrderActor {
  case class Collaborators(wallet: ActorRef,
                           paymentProcessor: ActorRef,
                           submissionSupervisor: ActorRef,
                           gateway: ActorRef,
                           bitcoinPeer: ActorRef)

  trait Delegates[C <: FiatCurrency] {
    def exchangeActor(exchange: ExchangeActor.ExchangeToStart[C], resultListener: ActorRef): Props
    def orderFundsActor: Props
  }

  case class CancelOrder(reason: String)

  /** Ask for order status. To be replied with an [[Order]]. */
  case object RetrieveStatus

  def props[C <: FiatCurrency](exchangeActorProps: ExchangeActorProps,
                               network: NetworkParameters,
                               amountsCalculator: AmountsCalculator,
                               order: Order[C],
                               collaborators: Collaborators): Props = {
    val delegates = new Delegates[C] {
      override def exchangeActor(exchange: ExchangeToStart[C], resultListener: ActorRef) = {
        import collaborators._
        exchangeActorProps(exchange, ExchangeActor.Collaborators(
          wallet, paymentProcessor, gateway, bitcoinPeer, resultListener))
      }
      override def orderFundsActor = OrderFundsActor.props(
        collaborators.wallet, collaborators.paymentProcessor)
    }
    Props(new OrderActor[C](
      order,
      publisher => new OrderController(amountsCalculator, network, order, publisher),
      delegates,
      collaborators
    ))
  }
}
