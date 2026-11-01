//! The Automagik usage meter: token usage of coding agents on this machine, kept as `ae/1`
//! (agent event envelope, revision 2, owned by automagik-herdr) `agent.usage` rows in a local
//! SQLite file, and published as a small summary doc in herdr-core.
//!
//! Nothing here opens a network connection; the only process it talks to is the local `hc`.

pub mod event;
pub mod hc;
pub mod paths;
pub mod prices;
pub mod repo;
pub mod scan;
pub mod sources;
pub mod store;
pub mod summary;
pub mod sync;
pub mod time;
