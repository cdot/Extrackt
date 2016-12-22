#!/usr/bin/perl
use strict;
use warnings;
use MP3::Tag;
use String::Compare;
use File::Copy;
use File::Find;
use Cwd qw(chdir);
use Encode ();

my %map = (
    "Bach, Johann Sebastian" => "Bach, J. S.",
    "John Lennon and Paul McCartney" => "Lennon & McCartney"
    );
my $respmoc = qr/^(.*\S)\s+(\S+)$/;
my $target_dir = "/massive/Music";

binmode(STDOUT, "encoding(utf8)");
binmode(STDIN, "encoding(utf8)");
binmode(STDERR, "encoding(utf8)");

sub isThere {
    my ($dir, $comma) = @_;
    return $dir if -e "$target_dir/$dir";
    return $map{$dir} if $map{$dir};
    return undef unless $comma;
    return undef unless ($dir =~ s/$respmoc/$2, $1/o);
    return $dir if -e "$target_dir/$dir";
    return $map{$dir} if $map{$dir};
    return undef;
}

my $knowndirs;

my $noPrompt = 0;
foreach my $a (@ARGV) {
    if ($a eq '-np') {
        $noPrompt = 1;
    }
}

File::Find::find(\&wanted, '.');

sub wanted {
    return unless $_ =~ /\.mp3$/;
    
    my $f = $_;
    my $mp3 = MP3::Tag->new($f);
    return unless $mp3;
    my $artist = $mp3->artist();
    my $composer = $mp3->composer();
    my $title = $mp3->title();
    print "--------------\n$f\nTitle: $title\nArtist: $artist\nComposer: $composer\n";

    # See if we know the composer
    my $dir = isThere($composer, 1);
    if (!$dir) {
        $dir = isThere($artist, 0);
        if (!$dir) {
            return if ( $noPrompt );
            my @picks = ($composer, $artist);

            my @best;
            unless ($knowndirs) {
                my $lh;
                opendir($lh, $target_dir) || die $!;
                $knowndirs = [ grep { !/^\./ } readdir($lh) ];
                closedir($lh);
            }
            my %seen;
            foreach my $pick (@picks) {
                next unless $pick;
                foreach my $knar (@$knowndirs) {
                    my $weight = String::Compare::compare($pick, $knar);
                    if (scalar(@best) < 8) {
                        push(@best, { weight => $weight, key => $knar });
                        @best = sort { $a->{weight} <=> $b->{weight} } @best;
                        %seen = ( map { $_->{key} => $_ } @best );
                    }
                    elsif ($weight > $best[0]->{weight} &&
                           !($seen{$knar} && $seen{$knar}->{weight} eq $weight)) {
                        $best[0] = { weight => $weight, key => $knar };
                        @best = sort { $a->{weight} <=> $b->{weight} } @best;
                        %seen = ( map { $_->{key} => $_ } @best );
                    } else {
                        next;
                    }
                }
            }
            push(@best, { key => $composer }) if $composer;
            if ($composer =~ /$respmoc/) {
                push(@best, { key => "$2, $1", weight => 0 });
            }
            push(@best, { key => $artist, weight => 0 }) if $artist;
            push(@best, { key => 'Various Artists', weight => 0 })
                if $artist =~ /(,|&|;|\/| and )/i;
            push(@best, { key => 'Miscellaneous', weight => 0 });
            push(@best, { key => 'Trad', weight => 0 })
                if ($composer =~ /traditional/i || $composer =~ /^Trad/);
            @best = reverse @best;
            while (1) {
                my $n = 0;
                print "Could it be one of these?:\n" .
                    join("\n", map { "\t".($n++).". $_->{key} ".(-d "$target_dir/$_->{key}" ? "" : "(does not exist)") } @best)."\n";
                print "Select number or type new name: ";
                my $choice = <STDIN>; chomp($choice);
                if ($choice =~ /^\s*(\d+)\s*$/) {
                    $dir = $best[$1]->{key};
                    last;
                } else {
                    unshift(@best, { key => $choice });
                }
            }
        }
    }
    if (-e "$target_dir/$dir/$f") {
        print STDERR "$dir/$f already exists\n";
    } else {
        print "Move to $dir\n";
        unless ( -d "$target_dir/$dir" ) {
            mkdir "$target_dir/$dir";
            push(@$knowndirs, $dir) if $knowndirs;
        }
        File::Copy::move($f, "$target_dir/$dir/$f") || die $!; 
    }
}
