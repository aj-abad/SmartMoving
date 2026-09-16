# Research: Korean Comment Translation for 4 Medium-Sized Files

## Objective
Translate all Korean comments in 4 Java files to English while preserving code logic, variable names, and comment structure.

## Files to Translate
1. `SmartMovingSwimmer.java` (Client-side swimming/diving physics)
2. `SmartMovingJumper.java` (Client-side jumping mechanics)
3. `SmartMovingServer.java` (Server-side player state management)
4. `SmartStatistics.java` (Animation and movement statistics tracking)

## Translation Approach
- Comments use both `//` (line) and `/* */` (block) styles
- Preserve exact formatting and indentation
- Technical terms should be consistent with existing English translations in the codebase
- Include context indicators like phase names, session numbers, and bug fix references
- Maintain Korean code elements (variable names, method names, class names) unchanged

## Comment Categories Identified

### Swimming/Diving Physics (SmartMovingSwimmer.java)
- Water state classification (dipping, swimming, diving)
- Movement physics with damping values
- Water movement ticks tracking
- Water border calculations
- Shallow water transitions

### Jumping Mechanics (SmartMovingJumper.java)
- Jump type constants (15 varieties)
- Jump speed calculations
- Head jump pose management
- Wall jumping state updates
- Collision angle calculations
- Special cases and fixes for user-reported issues

### Server-Side State (SmartMovingServer.java)
- Player state synchronization
- Packet decoding and state updates
- Dimension calculations for hitbox
- Crawling/sliding/head jumping transitions
- Fall distance reset conditions
- Exhaustion/hunger management
- Configuration state logging

### Statistics & Animation (SmartStatistics.java)
- Cumulative distance tracking
- Speed normalization
- Lerp interpolation for smooth animation
- Distance backward jump prevention
- Historical data management

## Key Technical Terms (English Equivalents)
- 수영 → Swimming
- 잠수 → Diving
- 담금질 / 담그기 → Dipping
- 크롤링 → Crawling
- 점프 → Jump
- 벽점프 → Wall Jump
- 헤드점프 → Head Jump
- 낙하 → Fall/Falling
- 상태 → State
- 상자 → Box/Bounding Box
- 패킷 → Packet
- 포즈 → Pose
- 박스 박힘 → Box collision/jammed

## Notes
- Files contain extensive phase/session annotations referencing original SmartMoving 1.7.10 and 1.12.2 implementations
- Many comments include bug fix references (fix #XX) with explanations
- Keep these references as-is while translating the descriptive text
- Comments often explain "원본" (original) behavior to maintain accuracy
