package com.wellsey.clack

/**
 * Starter next-word predictions, so suggestions are useful from day one.
 * Clack adds what you actually write on top of these as you type.
 * "<s>" means the start of a sentence. Keys can be one word or two ("see you").
 */
object StarterPredictions {
    const val START = "<s>"

    private const val TABLE = """
<s>: i thanks hi hey yes no the it ok
i: think will am have was don't just love can
i'm: not going so just sure on in at
i'll: be have let see do send call get
i've: been got just never seen
i'd: love like be have
you: can are have know want too
you're: welcome right so not a
we: can are have will should could need
we're: going on at not in
they: are were have said will
it: is was will would
it's: a not ok fine so been the
that: is was would sounds
that's: great fine ok good a the it
is: it that the a there this
are: you we they there
was: a the it so just
can: you i we be
could: you i we be
would: you be like love have
will: be do have you
do: you it we that
don't: know think have worry want
didn't: know have want get
can't: wait be believe
thank: you
thanks: for so a very
see: you it if what
let: me us know
let's: go do get see
how: are is was about much long
how's: it your the things
what: do is time are about
what's: up the your
where: are is do
when: are is do you
going: to
want: to a
have: a to you been the
had: a to been
need: to a some
got: a to it the
get: a the to it home
on: the my my
in: the a my
at: the home work
for: the a you me
to: be the do go get see
of: the course my
and: i the then
but: i it the
so: much i the many
just: a got wanted
good: morning night luck day
great: thanks day idea
happy: birthday new to
merry: christmas
no: problem worries way thanks
yes: please that i
ok: thanks i see
okay: thanks i
sorry: i for about
love: you it
miss: you
call: me you
text: me you
talk: to soon later
speak: to soon later
hope: you you're it
good morning: how
see you: soon later tomorrow then
on my: way
how are: you
have a: good great nice lovely
"""

    /** context ("you" or "see you") → likely next words, best first. */
    val table: Map<String, List<String>> by lazy {
        val map = HashMap<String, List<String>>()
        for (line in TABLE.lines()) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim()
            map[key] = line.substring(colon + 1).trim().split(' ').filter { it.isNotEmpty() }.distinct()
        }
        map
    }
}
