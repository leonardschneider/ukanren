/**
  * From the paper by Jason Hemann
  *  
  * I've tried to stay true to the names in the original, sometimes
  * being more explicit or tweaking to avoid naming clashes.
  * TODO: Rename everything to avoid `$` symbol.
  *
  * Departures from the paper:
  * A substitution is represented by a Map rather than an association list.
  *
  */

trait Core {
  sealed trait $tream[+T]
  case class $Cons[+T](head: T, tail: $tream[T]) extends $tream[T]
  case class ImmatureStream[+T](proc: () => $tream[T]) extends $tream[T]
  case object $Nil extends $tream[Nothing]

  sealed trait LList

  case class LCons(head: Term, tail: LList) extends LList {
    override def toString = head+"::"+tail
  }

  case class LVar(index: Int) extends LList {
    override def toString = "_"+index
  }

  protected def lcons(head: Term, tail: Term): Term = tail match {
    case s: Seq[_] => head +: s
    case l: LList => LCons(head, l)
  }

  type Term = Any
  type Substitution = Map[LVar, Term]
  case class State(substitution: Substitution, counter: Int)
  type Goal = State => $tream[State]

  val succeed: Goal = state => unit(state)
  val fail: Goal = state => mzero
  val emptyState = State(Map.empty, 0)

  protected def callFresh(f: LVar => Goal): Goal = {
    case State(s,c) => f(LVar(c))(State(s, c+1))
  }

  def unit(state: State): $tream[State] = $Cons(state, $Nil)
  val mzero: $tream[State] = $Nil

  def unify(u: Term, v: Term): Goal = { case State(s, c) =>
    unify(u, v, s).map(newSub => unit(State(newSub, c))).getOrElse(mzero)
  }

  protected def unify(u: Term, v: Term, s: Substitution): Option[Substitution] =
    (walk(u, s), walk(v, s)) match {
      case (u, v) if u == v => Some(s)
      case (u: LVar, v) => Some(s + (u -> v))
      case (u, v: LVar) => Some(s + (v -> u))
      case (us: Seq[_], vs: LCons) if us.nonEmpty =>
        unify(us.head, vs.head, s).flatMap(unify(us.tail, vs.tail, _))
      case (us: LCons, vs: Seq[_]) if vs.nonEmpty =>
        unify(us.head, vs.head, s).flatMap(unify(us.tail, vs.tail, _))
      case (us: LCons, vs: LCons) =>
        unify(us.head, vs.head, s).flatMap(unify(us.tail, vs.tail, _))
      case (us: Seq[_], vs: Seq[_]) if us.nonEmpty && vs.nonEmpty =>
        unify(us.head, vs.head, s).flatMap(unify(us.tail, vs.tail, _))
      case _ => None
    }

  def walk(u: Term, s: Substitution): Term = u match {
    case v: LVar => s.get(v).fold(u)(walk(_, s))
    case _ => u
  }

  // The returned result is alway a fresh LVar or ground value
  // ie., a returned LVar is never bound
  // TODO: This property should be tested.
  def walk_*(v: Term, s: Substitution): Term = walk(v, s) match {
    case vs: Seq[_] => vs.map(walk_*(_, s))
    case LCons(h, t) => lcons(walk_*(h, s), walk_*(t, s)) //Document: Why not construct LCons
    case v => v
  }

  protected def disj(g1: => Goal, g2: => Goal): Goal = state => mplus(g1(state), g2(state))

  def mplus($1: $tream[State], $2: $tream[State]): $tream[State] = $1 match {
    case $Nil => $2
    case ImmatureStream(imm) => immature(mplus($2, imm()))
    case $Cons(h, t) => $Cons(h, mplus($2, t))
  }

  protected def conj(g1: => Goal, g2: => Goal): Goal = state => bind(g1(state), g2)

  def bind($: $tream[State], g: Goal): $tream[State] = $ match {
    case $Nil => mzero
    case ImmatureStream(imm) => immature(bind(imm(), g))
    case $Cons(h, t) => mplus(g(h), bind(t, g))
  }

  protected def immature[T]($: => $tream[T]) = ImmatureStream(() => $)

  // Inverse eta delay. Pronounced "Snooze"
  // TODO? Use the type system to decide when to do this implicitly
  protected def Zzz(g: Goal): State => ImmatureStream[State] = state => immature(g(state))

  def pull[T]($: $tream[T]): Stream[T] = $ match {
    case $Nil => Stream.empty
    case ImmatureStream(imm) => pull(imm())
    case $Cons(h, t) => h #:: pull(t)
  }
}

trait Interface extends Core {
  implicit class ByName[T](value: => T) {
    def apply(): T = value
  }

  implicit class TermOps(t: Term) {
    def ===(t2: Term): Goal = unify(t, t2)
  }

  implicit class GoalOps(g: Goal) {
    def |||(g2: Goal): Goal = disj(Zzz(g), Zzz(g2))
    def &&&(g2: Goal): Goal = conj(Zzz(g), Zzz(g2))
  }

  def disj_*(goals: ByName[Goal]*): Goal =
    goals.headOption.fold(fail)(head =>
      disj(Zzz(head()), disj_*(goals.tail: _*)))

  def conj_*(goals: ByName[Goal]*): Goal =
    goals.headOption.fold(succeed)(head =>
      conj(Zzz(head()), conj_*(goals.tail: _*)))

  def fresh(f: () => Goal): Goal = f()

  def fresh(f: (LVar) => Goal): Goal =
    callFresh(f)

  def fresh(f: (LVar, LVar) => Goal): Goal =
    callFresh(q =>
      callFresh(r => f(q,r)))

  // TODO: This is mechanical enough to be a macro
  def fresh(f: (LVar, LVar, LVar) => Goal): Goal =
    callFresh(q =>
      callFresh(r =>
        callFresh(s => f(q,r,s))))

  def reify(lvars: LVar*)(state: State): Seq[Term] = {
    def uniqueInOrder[T](items: Seq[T]): Seq[T] = items
      .zipWithIndex
      .groupBy(_._1)
      .toList
      .sortBy{case (item, indices) => indices.map(_._2).min}
      .map(_._1)

    def freshIndices(term: Term): Seq[Int] = term match {
      case LVar(index) => Seq(index)
      case LCons(h, t) => freshIndices(h) ++ freshIndices(t)
      case vs: Seq[_] => vs.flatMap(freshIndices)
      case _ => Seq()
    }

    val walkedValues = lvars.map(walk_*(_, state.substitution))
    val newId: Map[Int, Int] = uniqueInOrder(walkedValues.flatMap(freshIndices))
      .zipWithIndex.toMap

    // ideally, reindexVars would preserve type info
    def reindexVars(term: Term): Term = term match {
      case LVar(id) => LVar(newId(id))
      case LCons(h, t) => lcons(reindexVars(h), reindexVars(t))
      case vs: Seq[_] => vs.map(reindexVars)
      case x => x
    }

    walkedValues.map(reindexVars)
  }

  def reifyS(lvars: LVar*)(state: State): String =
    reify(lvars: _*)(state).mkString("(", ", ", ")")

  def run_*(f: () => Goal): Stream[String] =
    pull(fresh(f)(emptyState)).map(reifyS())

  def run_*(f: (LVar) => Goal): Stream[String] =
    pull(fresh(f)(emptyState)).map(reifyS(LVar(0)))

  def run_*(f: (LVar, LVar) => Goal): Stream[String] =
    pull(fresh(f)(emptyState)).map(reifyS(LVar(0), LVar(1)))

  def run_*(f: (LVar, LVar, LVar) => Goal): Stream[String] =
    pull(fresh(f)(emptyState)).map(reifyS(LVar(0), LVar(1), LVar(2)))

  def reifyC[T](lvar: LVar)(state: State): Either[LVar, T] =
    reify(lvar)(state).head match {
      case l: LVar => Left(l)
      case t: Term => Right(t.asInstanceOf[T])
    }

  def reifyNestedC[F[_] <: Seq[_],T](lvar: LVar)(state: State): Either[LVar, F[Either[LVar, T]]] = {
    def toEitherLVarT(t: Any): Either[LVar, T] = t match {
        case l: LVar => Left(l)
        case t: Term => Right(t.asInstanceOf[T])
    }

    reify(lvar)(state).head match {
      case l: LVar => Left(l)
      case ls: F[_] => Right(
        ls.map(t => toEitherLVarT(t))
          .asInstanceOf[F[Either[LVar, T]]])
    }
  }

  def runC[T](f: (LVar) => Goal): Stream[Either[LVar, T]] =
    pull(fresh(f)(emptyState)).map(reifyC[T](LVar(0)))

  def runC[F[_] <: Seq[_], T](f: (LVar) => Goal)(implicit d: DummyImplicit)
      : Stream[Either[LVar, F[Either[LVar, T]]]] =
    pull(fresh(f)(emptyState)).map(reifyNestedC[F, T](LVar(0)))

  def conso(head: Term, tail: Term, out: Term): Goal = lcons(head, tail) === out
  
  def emptyo(l: Term): Goal = l === Nil

  def appendo(a: Term, b: Term, result: Term): Goal = disj_*(
    (emptyo(a) &&& (result === b)),
    fresh((h, t, tb) => conj_*(
      conso(h, t, a),
      appendo(t, b, tb),
      conso(h, tb, result))))
}

object ukanren extends Interface with Core
