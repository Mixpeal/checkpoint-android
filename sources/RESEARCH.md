# Source research: police stop of a motorist (Nigeria)

Captured 2026-09-19. Two tiers below. **Only Tier 1 is safe to cite as-is**, and even
then you must eyeball the section number in the PDF, because the text was OCR'd from a
scanned gazette and contains character errors.

---

## Tier 1: verified against primary text on disk

Source: `sources/police_act_2020_gazette.pdf` (Official Gazette scan, 70pp, has a text
layer) → extracted to `sources/police_act_2020.txt`.

### s.49: Power to stop and search (the checkpoint section)

> 49.—(1) A police officer may exercise the power to stop and search in any—
> (a) place the public or any section of the public has access, on payment or otherwise,
> as of right or by virtue of express or implied permission; or
> (b) other place to which the public has ready access at the time when he proposes to
> exercise the power but which is not a private residence.
>
> (2) A Police officer may detain and search any person or vehicle where—
> (a) reasonable grounds for suspicion exist that the person being suspected is having in
> his possession, or conveying in any manner anything which he has reason to believe to
> have been stolen or otherwise unlawfully obtained;
> (b) reasonable grounds for suspicion exist that such person or vehicle is carrying an
> unlawful article;
> (c) reasonable grounds for suspicion that incidents involving serious violence may take
> place within a locality;
> (d) information has been received as to a description of an article being carried or of a
> suspected offender; and
> (e) a person is carrying a certain type of article at an unusual time or in a place where a
> number of burglaries or thefts are known to have taken place recently.

**The operative words are "reasonable grounds for suspicion."** Every limb of s.49(2) is
conditioned on it. This is not a general power to stop everyone.

### s.52(5): no stripping in public

> (5) The powers conferred under this section to search a person are not to be construed
> as authorising a police officer to require a person to remove any of his clothing in public.

### s.62: bail after arrest without warrant

> 62.—(1) Where a suspect has been taken into police custody without a warrant for an
> offence other than an offence punishable with death, an officer in charge of a police
> station shall inquire into the case and release the suspect arrested on bail … where it
> will not be practicable to bring the suspect before a court … within 24 hours after the arrest.
>
> (2) The police officer in charge of a police station shall release the suspect on bail on his
> entering into a recognisance with or without sureties for a reasonable amount of money …

CAUTION on the popular "bail is free" claim. s.62(2) describes a **recognisance**, a
promise to forfeit money if you fail to appear. That is not a payment handed to police. These are
different things. Write this claim carefully or it will be wrong in a way people notice.

### s.48(4): human rights during search of premises

> (4) While searching the premises, a police officer shall not violate the human rights of
> persons found in the premises that is being searched.

### s.56 to s.57: a search must be recorded

> 56.—(1) An officer who has carried out a search shall make a written record unless it is
> not practicable to do so …

### Absence findings: verified by full-text search

| Term | Occurrences in Police Act 2020 |
|---|---|
| checkpoint / roadblock / road block | **0** |
| phone / mobile / device / computer / digital / password / unlock | **0** |

Two things follow, and both are usable claims:

1. **"Checkpoint" is not a statutory concept.** The Act confers a power to *stop and search
   on reasonable suspicion*; it does not create or regulate roadblocks as such.
2. **The Act is silent on phones.** s.49 speaks of searching "any person or vehicle." It says
   nothing about compelling a person to unlock or hand over a device. That silence is the
   heart of the phone question and is why it belongs in `contested`, not `settled`.

---

## Tier 2: secondary, VERIFY before shipping

Not yet confirmed against primary text. Do not cite until you have.

- **Constitution 1999, s.37**, reported as: "The privacy of citizens, their homes,
  correspondence, telephone conversations and telegraphic communications is hereby
  guaranteed and protected." Chapter IV (Fundamental Rights). Note s.45 permits derogation
  "in accordance with law … reasonably justifiable in a democratic society."
- **Cybercrimes Act 2015, s.45**, reported as requiring law enforcement to apply to a judge
  by **ex-parte application** for a warrant to search, seize, remove or detain anything
  containing evidence of an offence under that Act.
- **SARS**, disbanded 11 October 2020. **SWAT** announced by the IGP 13 October 2020 as
  replacement. Current 2026 operational status NOT established by this research. If a claim
  turns on whether SARS still exists, that needs a current, dated source.

---

## Trap found: do not use

`nigerianlawguru.com/wp-content/uploads/2024/06/CYBERCRIME-ACT-2015.pdf` is titled as the
2015 Act but its body reads **"A BILL FOR AN ACT … 2013"** and it stops at 43 sections. The
enacted Act has 59 and its s.45 is the search/seizure provision. Citing this file cites the
wrong instrument.

`cert.gov.ng` (the government host) returns 403 to automated requests. Fetch it in a browser.

---

## Suggested bucketing: YOUR call, not mine

These are my reading of the sources, not legal advice. A lawyer should check every one.

| Candidate claim | Likely bucket | Anchor |
|---|---|---|
| Police may stop and search you in a public place | settled | Police Act 2020 s.49(1) |
| They need reasonable suspicion to search you or your vehicle | settled | s.49(2) |
| They can make you undress at the roadside | folk | s.52(5) |
| A search should be recorded in writing | settled | s.56(1) |
| They can compel you to unlock your phone at a stop | **contested** | Act silent; Constitution s.37; Cybercrimes Act s.45 |
| "Bail is free" | **contested / needs care** | s.62(2) recognisance ≠ fee |
| Traffic offence fines are police jurisdiction | **contested** | FRSC Establishment Act, NOT YET RESEARCHED |

The last row is your own story and it is not yet sourced. The FRSC Establishment Act 2007 is
the instrument to pull next.
