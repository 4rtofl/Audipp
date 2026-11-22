use std::{
    fs::File,
    path::PathBuf,
    sync::{Arc, Condvar, Mutex, mpsc},
    thread,
    time::Duration,
};

use librespot_core::{Session, SpotifyUri, config::SessionConfig};
use librespot_metadata::audio::AudioFileFormat;
use librespot_playback::{
    NUM_CHANNELS, SAMPLE_RATE,
    audio_backend::{Sink, SinkError, SinkResult},
    config::PlayerConfig,
    convert::Converter,
    decoder::{AudioDecoder, AudioPacket, PassthroughDecoder, SymphoniaDecoder},
    mixer::NoOpVolume,
    player::{Player, PlayerEvent},
};
use symphonia::core::probe::Hint;

const WAIT: Duration = Duration::from_secs(5);
const MAX_SAMPLES: usize = 16_384;

fn fixture(name: &str) -> PathBuf {
    PathBuf::from(std::env::var_os("AUDIPP_SPOTIFY_FIXTURES").unwrap()).join(name)
}

fn decoder(name: &str) -> SymphoniaDecoder {
    let file = File::open(fixture(name)).unwrap();
    SymphoniaDecoder::new(file, Hint::new()).unwrap()
}

#[derive(Default)]
struct Queue {
    generation: u64,
    block: Option<Vec<f64>>,
}

#[derive(Clone)]
struct FixtureSink {
    shared: Arc<(Mutex<Queue>, Condvar)>,
    generation: u64,
    waiting: mpsc::SyncSender<()>,
}

impl Sink for FixtureSink {
    fn write(&mut self, packet: AudioPacket, _: &mut Converter) -> SinkResult<()> {
        let AudioPacket::Samples(samples) = packet else {
            return Err(SinkError::InvalidParams("Raw is not PCM".into()));
        };
        if samples.is_empty()
            || samples.len() > MAX_SAMPLES
            || samples.len() % usize::from(NUM_CHANNELS) != 0
            || !samples.iter().all(|value| value.is_finite())
        {
            return Err(SinkError::InvalidParams("invalid stereo samples".into()));
        }

        let (lock, changed) = &*self.shared;
        let mut queue = lock.lock().unwrap();
        while queue.generation == self.generation && queue.block.is_some() {
            let _ = self.waiting.try_send(());
            queue = changed.wait(queue).unwrap();
        }
        if queue.generation == self.generation {
            queue.block = Some(samples);
        }
        Ok(())
    }
}

fn sink() -> (FixtureSink, mpsc::Receiver<()>) {
    let (waiting, waited) = mpsc::sync_channel(1);
    let sink = FixtureSink {
        shared: Arc::default(),
        generation: 0,
        waiting,
    };
    (sink, waited)
}

fn take(sink: &FixtureSink) -> Option<Vec<f64>> {
    let (lock, changed) = &*sink.shared;
    let block = lock.lock().unwrap().block.take();
    changed.notify_all();
    block
}

fn invalidate(sink: &FixtureSink) -> u64 {
    let (lock, changed) = &*sink.shared;
    let mut queue = lock.lock().unwrap();
    queue.generation += 1;
    queue.block.take();
    changed.notify_all();
    queue.generation
}

#[test]
fn decoded_stereo_frames_survive_packet_transfer_and_decoder_drop() {
    let mut decoder = decoder("player/stereo.flac");
    let (mut sink, _) = sink();
    let mut converter = Converter::new(None);
    let mut blocks = Vec::new();
    let mut previous_ms = 0;
    while let Some((position, packet)) = decoder.next_packet().unwrap() {
        assert!(position.position_ms >= previous_ms);
        assert!(!position.skipped);
        previous_ms = position.position_ms;
        sink.write(packet, &mut converter).unwrap();
        blocks.push(take(&sink).unwrap());
    }
    drop(decoder);

    let pcm: Vec<_> = blocks.into_iter().flatten().collect();
    assert_eq!((SAMPLE_RATE, NUM_CHANNELS), (44_100, 2));
    assert_eq!(pcm.len(), 88_200);
    for (index, frame) in pcm.chunks_exact(2).enumerate() {
        let sign = if index < 22_050 { 1.0 } else { -1.0 };
        assert_eq!(frame, [sign * 0.25, sign * -0.5]);
    }
    println!("stereo FLAC: 44100 Hz, 2 channels, 44100 frames, exact L/R values");
}

#[test]
fn unsupported_format_and_invalid_packets_are_rejected() {
    for (name, error) in [
        ("48k.flac", "Unsupported sample rate: 48000"),
        ("mono.flac", "Unsupported number of channels"),
    ] {
        let file = File::open(fixture(name)).unwrap();
        let result = SymphoniaDecoder::new(file, Hint::new());
        assert!(result.err().unwrap().to_string().contains(error));
    }

    let file = File::open(fixture("stereo.ogg")).unwrap();
    let mut decoder = PassthroughDecoder::new(file, AudioFileFormat::OGG_VORBIS_160).unwrap();
    let (_, raw) = decoder.next_packet().unwrap().unwrap();
    assert!(raw.raw().unwrap().starts_with(b"OggS"));
    assert!(raw.samples().is_err());

    let (mut sink, _) = sink();
    let mut converter = Converter::new(None);
    for packet in [
        raw,
        AudioPacket::Samples(vec![]),
        AudioPacket::Samples(vec![0.0]),
        AudioPacket::Samples(vec![0.0, f64::NAN]),
        AudioPacket::Samples(vec![f64::INFINITY, 0.0]),
        AudioPacket::Samples(vec![0.0; MAX_SAMPLES + 2]),
    ] {
        assert!(matches!(
            sink.write(packet, &mut converter),
            Err(SinkError::InvalidParams(_))
        ));
    }
    assert!(take(&sink).is_none());
}

#[test]
fn draining_one_slot_resumes_the_same_generation() {
    let (mut sink, waited) = sink();
    let reader = sink.clone();
    sink.write(
        AudioPacket::Samples(vec![0.25, -0.5]),
        &mut Converter::new(None),
    )
    .unwrap();
    let (done, result) = mpsc::channel();
    let worker = thread::spawn(move || {
        let packet = AudioPacket::Samples(vec![-0.25, 0.5]);
        done.send(sink.write(packet, &mut Converter::new(None)))
            .unwrap();
    });
    waited.recv_timeout(WAIT).unwrap();
    assert_eq!(take(&reader).unwrap(), [0.25, -0.5]);
    result.recv_timeout(WAIT).unwrap().unwrap();
    worker.join().unwrap();
    assert_eq!(take(&reader).unwrap(), [-0.25, 0.5]);
}

#[test]
fn seek_discards_in_flight_pcm_before_accepting_the_new_generation() {
    let (mut sink, waited) = sink();
    let mut reader = sink.clone();
    let mut decoder = decoder("player/stereo.flac");
    let mut converter = Converter::new(None);
    sink.write(decoder.next_packet().unwrap().unwrap().1, &mut converter)
        .unwrap();
    let old_packet = decoder.next_packet().unwrap().unwrap().1;
    let (done, result) = mpsc::channel();
    let worker = thread::spawn(move || {
        done.send(sink.write(old_packet, &mut Converter::new(None)))
            .unwrap();
    });
    waited.recv_timeout(WAIT).unwrap();
    let generation = invalidate(&reader);
    result.recv_timeout(WAIT).unwrap().unwrap();
    worker.join().unwrap();
    assert!(take(&reader).is_none());

    let actual_ms = decoder.seek(750).unwrap();
    let (position, packet) = decoder.next_packet().unwrap().unwrap();
    assert_eq!(position.position_ms, actual_ms);
    assert!((500..=750).contains(&actual_ms));
    reader.generation = generation;
    reader.write(packet, &mut Converter::new(None)).unwrap();
    let pcm = take(&reader).unwrap();
    assert!(!pcm.is_empty());
    assert!(pcm.chunks_exact(2).all(|frame| frame == [-0.25, 0.5]));
    println!("seek 750 ms: decoded packet starts at {actual_ms} ms");
}

#[tokio::test]
async fn the_real_player_delivers_local_pcm_and_can_stop_after_cancelling_a_full_sink() {
    let (sink, waited) = sink();
    let reader = sink.clone();
    let config = PlayerConfig {
        local_file_directories: vec![fixture("player")],
        ditherer: None,
        ..PlayerConfig::default()
    };
    let player = Player::new(
        config,
        Session::new(SessionConfig::default(), None),
        Box::new(NoOpVolume),
        move || Box::new(sink),
    );
    let mut events = player.get_player_event_channel();
    player.load(
        SpotifyUri::Local {
            artist: String::new(),
            album_title: String::new(),
            track_title: "audipp-fixture".into(),
            duration: Duration::from_secs(1),
        },
        true,
        0,
    );

    let full = waited.recv_timeout(WAIT);
    let samples_match = reader
        .shared
        .0
        .lock()
        .unwrap()
        .block
        .as_ref()
        .is_some_and(|pcm| pcm.starts_with(&[0.25, -0.5]));
    invalidate(&reader);
    player.stop();
    full.expect("Player did not reach the full sink");
    assert!(samples_match);
    tokio::time::timeout(WAIT, async {
        while let Some(event) = events.recv().await {
            match event {
                PlayerEvent::Stopped { .. } => return,
                PlayerEvent::Unavailable { .. } => panic!("local fixture unavailable"),
                _ => (),
            }
        }
        panic!("player event channel closed before Stop");
    })
    .await
    .expect("Player could not process Stop");
    drop(player);
    assert!(take(&reader).is_none());
}
