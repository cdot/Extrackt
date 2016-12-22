use strict;
use warnings;
use MP3::Tag;
use File::Copy;
use Encode ();

binmode(STDOUT, "encoding(utf8)");
binmode(STDIN, "encoding(utf8)");
binmode(STDERR, "encoding(utf8)");

sub repair_utf8 {
    my $f = shift;
    my $df;
    my $sf = join(' ', map { ord ($_) } split(//, $f));
    eval {
        # Convert to a byte string
        utf8::downgrade($f);
        $df = Encode::decode('utf-8', $f, Encode::FB_CROAK);
    };
    if ($@) {
        eval {
            $df = Encode::decode('iso-8859-1', $f, Encode::FB_CROAK);
        }
    }
    return undef unless $df;
    my $sdf = join(' ', map { ord ($_) } split(//, $df));
    return undef if ($sdf eq $sf);
    print STDERR "REPAIR\n$sf\n$sdf\n";
    return $df;
}

sub repair_mp3 {
    my $f = shift;
    my $mp3 = MP3::Tag->new($f);
    return unless $mp3;
    my $artist = $mp3->artist();
    my $composer = $mp3->composer();
    my $title = $mp3->title();
    my $ra = repair_utf8($artist);
    my $rc = repair_utf8($composer);
    my $rt = repair_utf8($title);

    if ($ra || $rc || $rt) {
        my $cmd = 'id3v2';
        if ($ra) {
            $cmd .= " --TPE1 \"$ra\"";
        }
        if ($rc) {
            $cmd .= " --TCOM \"$rc\"";
        }
        if ($rt) {
            $cmd .= " --TIT2 \"$rt\"";
        }
        $cmd .= " '$f'";
        print STDERR "Fixing tags... $cmd\n";
        print STDERR `$cmd`;
    }
}

sub repair_dir {
    my $dir = shift;
    my $d;
    opendir($d, $dir) or die $!;
    foreach my $f (sort readdir $d) {
        next if $f =~ /^\./;
        if ($f =~ /Before/) {
            die "WANK ".join(' ', map { ord($_) } split(//, $f))."\n"
                .Encode::decode_utf8($f)."\n";

        }
        my $rf = repair_utf8($f);
        if ($rf) {
            print STDERR "Fixing file $f = $rf\n";
            File::Copy::move("$dir/$f", "$dir/$rf");
            $f = $rf;
        }
        if (-d "$dir/$f") {
            repair_dir("$dir/$f");
        } else {
            repair_mp3("$dir/$f");
        }
    }
}

repair_dir('.');

1;
