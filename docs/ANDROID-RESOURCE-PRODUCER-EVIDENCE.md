# Android resource producer byte evidence

The AGP application processResources witness retains its existing sources, manifest,
options, compiler and R.jar rows. It additionally records each supplied resource root
with the android-resources fingerprint role. This role hashes all regular file bytes
and sorted member names, including XML. Declared absent roots are recorded; absent
and empty directories are equivalent, while adding an XML member changes the hash.
Symbolic inputs remain rejected.

These rows prove the recorded roots' bytes at the producer's completion observation.
They do not establish completeness of AGP's supplied root inventory, or protect against
an input changing during the producer execution and changing back before observation.
The snapshot retains an explicit inventory-unverified limitation unless the separate
resource binding adapter matches the complete selected main-resource inventory,
namespace options and witnessed selected R.jar output. Successful binding admission resolves
that limitation for its recorded scope; unavailable capture or size fallback restores it.
A current directory-watch fingerprint cannot replace producer evidence.

Legacy producer witnesses without android-resources remain usable for ordinary
bytecode queries. New snapshot capture reports resource-producer-evidence-unavailable
for them. Previously serialized snapshots are not rewritten on read; resource impact
must treat missing resource evidence as unavailable. Use the same analyzer version
to capture and verify: older readers do not know this role's absent-directory semantics.

The existing AGP 8 application check invokes a bounded tamper regression after a real
producer and matched snapshot: an owned init-script doFirst hook changes XML at the snapshot boundary, after normal
producer dependencies complete. Only the snapshot task is forced out of up-to-date
reuse; no producer is excluded or marked successful artificially. A single bounded
receipt records witness/R.jar hashes immediately before injection and the exact XML
hash transition. The real snapshot task must reject changed-android-resources and
leave the prior snapshot, witness and R.jar unchanged. Restoring XML and rebuilding must return matched. This is producer
input consistency evidence, not XML file-selection support or layout inflation.
