fn main() -> Result<(), Box<dyn std::error::Error>> {
    std::env::set_var("PROTOC", protoc_bin_vendored::protoc_bin_path()?);
    let out = std::path::PathBuf::from(std::env::var("OUT_DIR")?);
    let source = "../../src/proto/standalone/v1/standalone.proto";
    println!("cargo:rerun-if-changed={source}");
    tonic_build::configure()
        .build_server(false)
        .file_descriptor_set_path(out.join("descriptor.bin"))
        .compile_protos(&[source], &["../../src/proto/standalone/v1"])?;
    let descriptor = std::fs::read(out.join("descriptor.bin"))?;
    pbjson_build::Builder::new()
        .register_descriptors(&descriptor)?
        .build(&[".mongot.standalone.v1"])?;
    Ok(())
}
