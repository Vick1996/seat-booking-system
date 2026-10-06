# Seat Reservation

A service that is the system of record for who owns each seat of an event, decided atomically while thousands of buyers compete for the same seats at on-sale time.

## Language

**Show**:
An event with a fixed set of numbered seats and one price per seat, sized like a cinema or theatre hall (at most 500 seats). Its name is only a label: two shows may share one.
_Avoid_: Event, concert, screening

**Seat**:
One uniquely labelled place within a show. It is either available, held, or confirmed, and is never owned by two people.
_Avoid_: Ticket, slot

**Reservation**:
The record of one successful request to buy one or more seats for a user. It owns those seats until it is cancelled.
_Avoid_: Booking, order, purchase

**Hold**:
A time-boxed claim on seats that the owner must confirm before it lapses. Only shows created with a hold window use holds; on every other show a reservation is a sale at once.
_Avoid_: Lock, temporary booking

**Confirmation**:
Turning a live hold into a sale. A hold that has lapsed cannot be confirmed.
_Avoid_: Payment, checkout

**Release**:
Giving a reservation's seats back so they can be bought again, either because the owner cancels or because a hold lapses.
_Avoid_: Refund, undo

**Caller**:
Whoever makes a request, identified only by their verified token. A caller is anonymous, a user, or an admin.
_Avoid_: Client, account

**Admin**:
A caller allowed to create shows. Everything else an admin does, they do as an ordinary user.
_Avoid_: Superuser, operator

**Idempotency key**:
A client-chosen token that makes a request count once per user, however many times it is retried.
_Avoid_: Request id, nonce

**Per-user limit**:
The most seats one user may own in a show at once.
_Avoid_: Quota, cap

**Hot seat**:
A seat that many buyers try to take at the same moment. Exactly one of them wins.
_Avoid_: Popular seat

**Decline**:
A normal, expected refusal of a request (seat taken, limit reached, key reused differently). It is never a server error.
_Avoid_: Failure, error

**Stampede**:
The burst of simultaneous requests when a show goes on sale.
_Avoid_: Spike, flash sale
