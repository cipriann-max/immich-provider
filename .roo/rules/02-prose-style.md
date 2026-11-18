# Prose Style

Applies to every string a human reads: README, CHANGELOG, commit messages, code comments,
KDoc, XML comments, resource strings, shell comments and workflow comments. It does not
apply to identifiers, generated code, or the vendored OpenAPI spec.

## Never use an em dash

Do not write an em dash (U+2014). Not in prose, not as a connector, not in pairs around an
aside. This file deliberately spells it as a codepoint so that a repository-wide search for
the character returns nothing. Rewrite the sentence instead. The usual replacements, in
order of preference:

- Break the sentence in two: `...the same call. It is required.`
- Use a colon when what follows explains what came before: `One place: the folder listing.`
- Use commas for a genuine aside: `the only surface, on purpose, that renders the library`
- Use parentheses for metadata: `API 26 (the minimum this app runs on)`
- Use a semicolon only when both clauses stand alone.

En dashes (U+2013) are allowed only inside numeric or alphabetic ranges such as
`96` to `256 px` or `A` to `Z`, and in a date range. Nowhere else. In prose, prefer `to`:
`96 to 256 px`. Inside a Markdown table, a range may keep the character.

Hyphens (`-`) are for compound words and are always correct: `read-only`, `third-party`.

## Avoid these constructions

Phrases and shapes that mark text as machine-written. They are not banned for being
fashionable, but because they pad a sentence without adding information, and this project
values a short accurate sentence over a flowing one.

- `It's not just X, it's Y` and `not merely`, `not only ... but also`. State Y directly.
- `It's worth noting that`, `Note that`, `Keep in mind that`, `It is important to note`.
  If it matters, say it. If it does not, delete the sentence.
- `This is a testament to`, `stands as`, `serves as a reminder`.
- `delve`, `leverage`, `utilise`, `utilize`, `seamless`, `robust`, `comprehensive`,
  `effortless`, `powerful`, `holistic`, `nuanced`, `pivotal`, `crucial`, `myriad`,
  `plethora`, `paradigm`, `underscore`, `showcase`, `foster`, `facilitate`, `streamline`,
  `elevate`, `unlock`, `empower`, `realm`, `landscape`.
- `In today's ...`, `In an era where ...`, `At the end of the day`.
- `In summary`, `To summarise`, `Overall,`, `In conclusion` as a closing paragraph.
- Rhetorical questions used as headings: `Why does this matter?`
- A closing sentence that restates the paragraph. Stop when the information stops.
- Three-item lists where two items are invented to make a rhythm. List what exists.
- Emphatic adverbs stacked on a plain verb: `actually`, `genuinely`, `truly`, `really`,
  `simply`, `just`. Delete them unless they contrast with something stated nearby.
- Adverb-first openers: `Interestingly`, `Importantly`, `Notably`, `Crucially`.
- Sentences that describe the act of writing instead of the subject: `This section
  explains how ...`, `Let's look at ...`, `Below we will see ...`.

## Prefer

- Concrete nouns and measured facts. `Three swipes produced zero queries` beats `scrolling
  does not appear to trigger a refresh`.
- The active voice with a named actor. `DocumentsUI re-sorts any root it renders` beats
  `any root is re-sorted`.
- Short declarative sentences. A long sentence that needs three commas to survive should
  be two sentences.
- Statements of what the code does and why, in that order. Reason first only when the
  reason is the surprising part.
- Specific numbers and file paths over vague references.

## Voice

Write as an engineer explaining a decision to another engineer. First person is fine in
commit messages and changelog entries (`I removed ...`), and impersonal is fine in the
README (`The app requests one permission`). Do not switch between them inside one document.

Do not address the reader as a hypothetical user of a product (`you will love how ...`).
Address them as someone who may need to modify the code.

## Commas and colons

- No serial comma before `and` unless it removes real ambiguity.
- Use a colon only when the second half explains or enumerates the first. Not as a
  substitute for `because`.
- Do not join two independent clauses with a comma. Use a full stop or a semicolon.
