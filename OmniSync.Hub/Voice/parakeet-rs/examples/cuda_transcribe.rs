use parakeet_rs::{ExecutionConfig, ExecutionProvider, ParakeetTDT, TimestampMode, Transcriber};
use std::env;
use std::time::Instant;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let start_time = Instant::now();
    let args: Vec<String> = env::args().collect();
    let audio_path = if args.len() > 1 {
        &args[1]
    } else {
        "6_speakers.wav"
    };

    println!("Using audio: {}", audio_path);

    let mut config = ExecutionConfig::new();
    
    #[cfg(feature = "cuda")]
    {
        println!("Enabling CUDA execution provider...");
        config = config.with_execution_provider(ExecutionProvider::Cuda);
    }
    #[cfg(not(feature = "cuda"))]
    {
        println!("CUDA feature not enabled, using CPU.");
    }

    let load_start = Instant::now();
    let mut parakeet = ParakeetTDT::from_pretrained("./tdt", Some(config))?;
    let load_duration = load_start.elapsed();
    println!("[TIMER] Model Load took {:.2}s", load_duration.as_secs_f32());

    let transcribe_start = Instant::now();
    let result = parakeet.transcribe_file(audio_path, Some(TimestampMode::Sentences))?;
    let transcribe_duration = transcribe_start.elapsed();
    println!("[TIMER] Transcription Work took {:.2}s", transcribe_duration.as_secs_f32());
    
    println!("\nTranscription:\n{}", result.text);

    println!("\nSentences:");
    for segment in result.tokens.iter() {
        println!(
            "[{:.2}s - {:.2}s]: {}",
            segment.start,
            segment.end,
            segment.text
        );
    }

    let elapsed = start_time.elapsed();
    println!(
        "\n✓ Transcription completed in {:.2}s",
        elapsed.as_secs_f32()
    );

    Ok(())
}